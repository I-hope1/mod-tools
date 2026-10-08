package nipx;

import nipx.util.CRC64;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.*;
import java.util.function.Function;

/**
 * 轻量级匿名内部类内容结构哈希器。
 *
 * <p>为匿名类的内容计算与类名序号无关的结构指纹，
 * 折叠进方法指纹中以消除过度归一化导致的哈希碰撞与语义错位。</p>
 */
public final class AnonClassHasher {
	/**
	 * <b>历史遗留常量，当前永不生效</b>。
	 *
	 * <p>{@link #hash} 内部**不会递归调用自身**（子匿名类的哈希只通过 {@code MethodFingerprinter}
	 * 的 {@code #ANON_relId_<childHash>#} 占位符间接参与，且在本类中因未调用
	 * {@code setAnonHashes} 而退化为无语的 {@code #ANON_relId#}），而三处调用点
	 * （{@code AnonClassAligner.parseInfos}、{@code LambdaAligner.scan}、回归套件）全都传
	 * {@code depth = 0}。因此 {@code if (depth > MAX_DEPTH) return null;} 是不可达分支，
	 * {@code visiting} 集合也永远不会超过一个元素。</p>
	 *
	 * <p>保留它是因为删掉会改动公开签名 {@code hash(..., int depth)}；但**不要**再把它当作
	 * "嵌套深度支持上限"来引用 —— 实测探针 {@code DeepNestProbe} 显示 depth 8 仍能正确对齐。
	 * 真正限制匹配质量的是下面第 3 节的方法签名（未屏蔽的 {@code <init>} 描述符）。</p>
	 */
	private static final int MAX_DEPTH = 4;

	private AnonClassHasher() { }

	/**
	 * 计算匿名类的内容哈希。
	 * @param anonClassName 内部名（如 {@code com/example/Foo$1}）
	 * @param anonBytes     字节码（为 null 则通过 resolver 获取）
	 * @param hostClassName 宿主外层类内部名（如 {@code com/example/Foo}）
	 * @param resolver      字节码解析器（用于读取关联/嵌套匿名类）
	 * @param cache         类名到计算结果的缓存
	 * @param visiting      当前调用栈中正在处理的类（防环）
	 * @param depth         递归深度
	 * @return 64 位哈希值；无法解析时返回 null
	 */
	public static Long hash(
	 String anonClassName,
	 byte[] anonBytes,
	 String hostClassName,
	 Function<String, byte[]> resolver,
	 Map<String, Long> cache,
	 Set<String> visiting,
	 int depth) {
		if (depth > MAX_DEPTH) return null;
		if (cache != null && cache.containsKey(anonClassName)) {
			return cache.get(anonClassName);
		}
		if (visiting != null && visiting.contains(anonClassName)) {
			return null;
		}

		if (anonBytes == null && resolver != null) {
			anonBytes = resolver.apply(anonClassName);
			if (anonBytes == null) {
				anonBytes = resolver.apply(anonClassName.replace('/', '.'));
			}
			if (anonBytes == null) {
				anonBytes = resolver.apply(anonClassName.replace('.', '/'));
			}
		}
		if (anonBytes == null || anonBytes.length == 0) {
			return null;
		}

		if (visiting == null) visiting = new HashSet<>();
		visiting.add(anonClassName);
		try {
			ClassNode cn = new ClassNode();
			new ClassReader(anonBytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

			long crc = CRC64.init();

			// 1. superName
			if (cn.superName != null) {
				crc = CRC64.updateStringUTF16(crc, cn.superName);
			}

			// 2. 排序后的 interfaces
			if (cn.interfaces != null && !cn.interfaces.isEmpty()) {
				List<String> sortedIfaces = new ArrayList<>(cn.interfaces);
				Collections.sort(sortedIfaces);
				for (String iface : sortedIfaces) {
					crc = CRC64.updateStringUTF16(crc, iface);
				}
			}

			// 字段描述符必须与方法描述符同源屏蔽：嵌套匿名类的 this$N:LOuter$K; 会随父类位移而变，
			// 不屏蔽则子类内容哈希必变、Tier 1/2 对嵌套层全面失效。注意 this$N 并非旧 JDK 专属：
			// javac 18+ 仅在外层实例**未被用到**时才省略它；嵌套匿名类一旦使用外层实例（读外层字段/
			// 调外层方法，生产最常见），8/11/17/21 都会生成 this$N。屏蔽是定向的：只改写 L本宿主$<纯数字>;。
			//
			// 单独用一个 fieldMasker：方法循环仍保持"整块一个 fp + 每方法 reset"的原状，
			// 避免把字段的 relId 消耗带进方法哈希（无 this$N 的类哈希必须逐字节不变）。
			MethodFingerprinter fieldMasker = new MethodFingerprinter();
			fieldMasker.setContext(hostClassName);

			// 3. 排序后的字段名和描述符（如 val$x, this$0）
			if (cn.fields != null && !cn.fields.isEmpty()) {
				List<String> fieldEntries = new ArrayList<>(cn.fields.size());
				for (FieldNode fn : cn.fields) {
					fieldEntries.add(fn.name + ":" + fieldMasker.maskDescriptor(fn.desc));
				}
				Collections.sort(fieldEntries);
				for (String fe : fieldEntries) {
					crc = CRC64.updateStringUTF16(crc, fe);
				}
			}

			// 4. 各方法的 MethodFingerprinter hash（排序后组合，不依赖方法在类中的排列位置）
			if (cn.methods != null && !cn.methods.isEmpty()) {
				// 单例隔离：new 独立实例，绝不污染外层 ThreadLocal 单例
				MethodFingerprinter fp = new MethodFingerprinter();
				// 极其关键：以 hostClassName 为上下文，将本匿名类自身引用归一为 #ANON_k#，消除数字位移
				fp.setContext(hostClassName);

				List<String> methodSignatures = new ArrayList<>();
				List<Long>   methodHashes     = new ArrayList<>();

				for (MethodNode mn : cn.methods) {
					fp.reset();
					fp.setContext(hostClassName);
					mn.accept(fp);
					long mHash = fp.getHash();

					// 非合成方法包含名字和描述符（如 run()V）
					if ((mn.access & Opcodes.ACC_SYNTHETIC) == 0 && !mn.name.startsWith("lambda$")) {
						// 描述符必须过一遍匿名类屏蔽：嵌套匿名类的构造器形如 `<init>(LOuter$1;)V`，
						// 父类一旦位移成 `Outer$2`，原始描述符就会变，于是子类哈希必然改变、
						// Tier 1/2 对该层全面失效（只剩不比字段表的 Tier 4）。屏蔽是**定向**的：
						// MethodFingerprinter.maskDescriptor 只改写 `L本宿主$<纯数字>;`，其余描述符原样返回。
						methodSignatures.add(mn.name + ":" + fp.maskDescriptor(mn.desc) + ":" + mHash);
					} else {
						methodHashes.add(mHash);
					}
				}

				Collections.sort(methodSignatures);
				Collections.sort(methodHashes);

				for (String ms : methodSignatures) {
					crc = CRC64.updateStringUTF16(crc, ms);
				}
				for (Long mh : methodHashes) {
					crc = CRC64.updateLong(crc, mh);
				}
			}

			long finalHash = CRC64.finish(crc);
			if (cache != null) {
				cache.put(anonClassName, finalHash);
			}
			return finalHash;
		} catch (Throwable t) {
			return null;
		} finally {
			visiting.remove(anonClassName);
		}
	}
}
