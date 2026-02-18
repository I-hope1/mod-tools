package nipx;

import nipx.util.Utils;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

import java.util.*;

/**
 * Lambda表达式对齐工具类
 * <p>
 * 主要功能：
 * 1. 分析新旧字节码中的合成方法（synthetic methods）
 * 2. 通过哈希匹配和顺序对齐算法，建立方法名映射关系
 * 3. 重写新字节码中的方法名引用，保持与旧版本的一致性
 * 4. 解决热交换时因Lambda表达式名称变化导致的NoSuchMethodError问题
 */
public class LambdaAligner {
	public static final int LAMBDA_LENGTH = 7;// "lambda$".length()

	public static final ThreadLocal<MatchContext> CONTEXT = ThreadLocal.withInitial(MatchContext::new);

	/** 匹配上下文，存储当前线程的匹配状态 */
	public static class MatchContext {
		/** 旧版本中的合成方法映射 */
		final Map<String, SyntheticInfo> oldMethods    = new LinkedHashMap<>(128);
		/** 新版本中的合成方法列表 */
		final List<SyntheticInfo>        newList       = new ArrayList<>(128);
		/** 方法名重命名映射表 */
		final Map<String, String>        renameMap     = new HashMap<>(64);
		/** 已使用的旧方法名集合 */
		final Set<String>                usedOldNames  = new HashSet<>(64);
		final MethodFingerprinter        fingerprinter = MethodFingerprinter.CONTEXT.get();
		/** 当前处理的类名 */
		String currentClass;

		/**
		 * 重置上下文状态，为下一次匹配做准备
		 */
		void reset() {
			oldMethods.clear();
			newList.clear();
			renameMap.clear();
			usedOldNames.clear();
			fingerprinter.reset();
			currentClass = null;
		}
	}

	/**
	 * 对齐Lambda表达式的主入口方法
	 * @param oldBytes 旧版本字节码
	 * @param newBytes 新版本字节码
	 * @return 对齐后的字节码，如果没有需要重命名则返回原始newBytes
	 */
	public static byte[] align(byte[] oldBytes, byte[] newBytes) {
		if (oldBytes == null || oldBytes.length == 0) return newBytes;

		MatchContext ctx = CONTEXT.get();
		ctx.reset();

		ctx.currentClass = scan(oldBytes, ctx, true);
		String newClass = scan(newBytes, ctx, false);
		if (!Objects.equals(ctx.currentClass, newClass)) {
			throw new IllegalArgumentException("New class name does not match old class name: " + newClass + " != " + ctx.currentClass);
		}

		Map<Long, List<SyntheticInfo>> oldGroups = groupByLogic(ctx.oldMethods.values());
		Map<Long, List<SyntheticInfo>> newGroups = groupByLogic(ctx.newList);

		for (Map.Entry<Long, List<SyntheticInfo>> entry : newGroups.entrySet()) {
			List<SyntheticInfo> newGroup = entry.getValue();
			List<SyntheticInfo> oldGroup = oldGroups.get(entry.getKey());
			if (oldGroup == null) continue;

			// Step 1: 精确 Hash 匹配 (O(N))
			Map<Long, LinkedList<SyntheticInfo>> hashIdx = new HashMap<>();
			for (SyntheticInfo oi : oldGroup) hashIdx.computeIfAbsent(oi.hash, _ -> new LinkedList<>()).add(oi);

			for (SyntheticInfo ni : newGroup) {
				LinkedList<SyntheticInfo> cand = hashIdx.get(ni.hash);
				if (cand != null && !cand.isEmpty()) {
					SyntheticInfo oi = cand.removeFirst();
					if (!ni.name.equals(oi.name)) ctx.renameMap.put(ni.name, oi.name);
					ni.matched = true;
					ctx.usedOldNames.add(oi.name);
				}
			}

			// Step 2: 顺序对齐 (解决 NoSuchMethodError 的关键)
			int oldPtr = 0;
			for (SyntheticInfo ni : newGroup) {
				if (ni.matched) continue;
				while (oldPtr < oldGroup.size() && ctx.usedOldNames.contains(oldGroup.get(oldPtr).name)) oldPtr++;
				if (oldPtr < oldGroup.size()) {
					SyntheticInfo oi = oldGroup.get(oldPtr);
					ctx.renameMap.put(ni.name, oi.name);
					ni.matched = true;
					ctx.usedOldNames.add(oi.name);
				}
			}
		}

		return ctx.renameMap.isEmpty() ? newBytes : applyTransform(newBytes, ctx);
	}

	/**
	 * 应用转换规则到字节码
	 * @param bytes 原始字节码
	 * @param ctx   匹配上下文
	 * @return 转换后的字节码
	 */
	private static byte[] applyTransform(byte[] bytes, MatchContext ctx) {
		ClassReader cr = new ClassReader(bytes);
		ClassWriter cw = new ClassWriter(0);

		Remapper remapper = new Remapper(Opcodes.ASM9) {
			/**
			 * 处理合成方法的定义和 Handle 引用
			 * 这是对齐 Lambda 的核心
			 */
			@Override
			public String mapMethodName(String owner, String name, String desc) {
				if (owner.equals(ctx.currentClass)) {
					return ctx.renameMap.getOrDefault(name, name);
				}
				return name;
			}

			/** 处理 $deserializeLambda$ 内部的方法名字符串常量 */
			@Override
			public Object mapValue(Object value) {
				if (value instanceof String s && s.length() > LAMBDA_LENGTH) {
					char c = s.charAt(0);
					if (c == 'l' || c == 'a') { // 快速预过滤 lambda$ 或 access$
						return ctx.renameMap.getOrDefault(s, s);
					}
				}
				return super.mapValue(value);
			}
		};

		cr.accept(new ClassRemapper(cw, remapper), ClassReader.EXPAND_FRAMES);
		return cw.toByteArray();
	}

	/**
	 * 扫描字节码并收集合成方法信息
	 * @param bytes 要扫描的字节码
	 * @param ctx   匹配上下文
	 * @param isOld 是否为旧版本
	 * @return 类名
	 */
	private static String scan(byte[] bytes, MatchContext ctx, boolean isOld) {
		final String[] className = new String[1];
		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public void visit(int version, int access, String name, String sig, String superName, String[] itfs) {
				className[0] = name;
			}

			@Override
			public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] exc) {
				// 构造函数和静态初始化块不处理
				if (name.startsWith("<")) {
					return null;
				}

				boolean isSynthetic    = (acc & Opcodes.ACC_SYNTHETIC) != 0;
				boolean matchesPattern = isSyntheticName(name);

				if (!isSynthetic && !matchesPattern) {
					return null; // 业务方法（如 aaa）直接跳过，不参与 hash 和 rename 映射
				}

				ctx.fingerprinter.reset();
				return new MethodVisitor(Opcodes.ASM9, ctx.fingerprinter) {
					@Override
					public void visitEnd() {
						String        logicalName = extractLogicalName(name);
						SyntheticInfo info        = new SyntheticInfo(name, desc, ctx.fingerprinter.getHash(), logicalName);
						if (isOld) {
							ctx.oldMethods.put(name, info);
						} else {
							ctx.newList.add(info);
						}
					}
				};
			}
		}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return className[0];
	}

	private static boolean isSyntheticName(String name) {
		if (name.length() < LAMBDA_LENGTH) return false;
		char c = name.charAt(0);
		// 性能优化，避免全量 contains 扫描
		return (c == 'l' && name.startsWith("lambda$")) ||
		       (c == 'a' && name.startsWith("access$")) ||
		       (c == '$' && name.equals("$deserializeLambda$"));
	}

	/**
	 * 从方法名中提取逻辑名称用于分组
	 * <pre>
	 * lambda$foo$bar -> lambda$foo
	 * access$100 -> access
	 * $deserializeLambda$ -> $deserializeLambda$
	 * </pre>
	 * @param name 合成方法名
	 * @return 逻辑名称
	 */
	private static String extractLogicalName(String name) {
		if (name.startsWith("lambda$")) {
			int lastDollar = name.lastIndexOf('$');
			return lastDollar > LAMBDA_LENGTH ? name.substring(0, lastDollar) : "lambda";
		}
		// 对于 access$100 等，逻辑名称就是其前缀，确保它们被归为一组顺序对齐
		if (name.startsWith("access$")) return "access";
		return name; // $deserializeLambda$ 等
	}


	/**
	 * 按逻辑名称和描述符对合成方法进行分组
	 * @param infos 合成方法信息集合
	 * @return 分组映射
	 */
	private static Map<Long, List<SyntheticInfo>> groupByLogic(Collection<SyntheticInfo> infos) {
		Map<Long, List<SyntheticInfo>> groups = new LinkedHashMap<>();
		for (SyntheticInfo info : infos) {
			// 使用逻辑名称+描述符作为分组键
			long key = Utils.computeCompositeHash(info.logicalName, info.desc);
			groups.computeIfAbsent(key, _ -> new ArrayList<>()).add(info);
		}
		return groups;
	}

	/**
	 * 合成方法信息类
	 */
	static class SyntheticInfo {
		/** 方法名 */
		String  name;
		/** 方法描述符 */
		String  desc;
		/** 逻辑名称（用于分组） */
		String  logicalName;
		/** 方法指纹哈希值 */
		long    hash;
		/** 是否已匹配 */
		boolean matched = false;

		SyntheticInfo(String n, String d, long h, String l) {
			name = n;
			desc = d;
			hash = h;
			logicalName = l;
		}
	}
}