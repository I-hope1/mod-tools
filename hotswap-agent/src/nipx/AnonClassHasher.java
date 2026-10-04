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
	private static final int MAX_DEPTH = 4;

	private AnonClassHasher() { }

	/**
	 * 计算匿名类的内容哈希。
	 *
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

			// 3. 排序后的字段名和描述符（如 val$x, this$0）
			if (cn.fields != null && !cn.fields.isEmpty()) {
				List<String> fieldEntries = new ArrayList<>(cn.fields.size());
				for (FieldNode fn : cn.fields) {
					fieldEntries.add(fn.name + ":" + fn.desc);
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
						methodSignatures.add(mn.name + ":" + mn.desc + ":" + mHash);
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
