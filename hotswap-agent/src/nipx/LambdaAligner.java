package nipx;

import nipx.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

import java.util.*;

/**
 * Lambda表达式对齐工具类，优先保证“不崩溃”，尽可能保证“逻辑严丝合缝”
 * <pre>
 * 主要功能：
 * 1. 分析新旧字节码中的合成方法（synthetic methods）
 * 2. 通过哈希匹配和顺序对齐算法，建立方法名映射关系
 * 3. 重写新字节码中的方法名引用，保持与旧版本的一致性
 * 4. 解决热交换时因Lambda表达式名称变化导致的NoSuchMethodError问题
 */
public class LambdaAligner {
	public static final int LAMBDA_LENGTH = 7;// "lambda$".length()

	public static final ThreadLocal<MatchContext> CONTEXT = ThreadLocal.withInitial(MatchContext::new);

	//region 匹配上下文
	/** 匹配上下文，存储当前线程的匹配状态 */
	public static class MatchContext {
		/** 方法名重命名映射表 */
		final Map<String, String> renameMap        = new HashMap<>(64);
		/** 已使用的旧方法名集合 */
		final Set<String>         usedOldNames     = new HashSet<>(64);
		final Set<String>         existingNewNames = new HashSet<>(64);
		final MethodFingerprinter fingerprinter    = MethodFingerprinter.CONTEXT.get();
		/** 当前处理的类名 */
		String currentClass;

		final LongObjectMap<List<SyntheticInfo>> oldGroups = new LongObjectMap<>(128);
		final LongObjectMap<List<SyntheticInfo>> newGroups = new LongObjectMap<>(128);

		//region 对象池管理
		// 预分配的一堆 ArrayList，用于复用
		private final List<List<SyntheticInfo>> pool    = new ArrayList<>();
		private       int                       poolIdx = 0;
		List<SyntheticInfo> acquireList() {
			if (poolIdx >= pool.size()) pool.add(new ArrayList<>(8));
			List<SyntheticInfo> list = pool.get(poolIdx++);
			list.clear();
			return list;
		}

		// 预分配的一堆 SyntheticInfo
		private final List<SyntheticInfo> infoPool    = new ArrayList<>();
		private       int                 infoPoolIdx = 0;
		SyntheticInfo acquireInfo(String n, String d, long h, String l) {
			if (infoPoolIdx >= infoPool.size()) {
				infoPool.add(new SyntheticInfo(n, d, h, l));
			}
			SyntheticInfo info = infoPool.get(infoPoolIdx++);
			info.update(n, d, h, l); // 增加一个更新方法
			return info;
		}
		//endregion

		/**
		 * 重置上下文状态，为下一次匹配做准备
		 */
		void reset() {
			renameMap.clear();
			usedOldNames.clear();
			existingNewNames.clear();
			fingerprinter.reset();
			currentClass = null;
			oldGroups.clear();
			newGroups.clear();
			poolIdx = 0;
			infoPoolIdx = 0;
		}
	}
	//endregion

	//region 主要API方法
	/**
	 * 对齐Lambda表达式的主入口方法
	 * @param oldBytes 旧版本字节码
	 * @param newBytes 新版本字节码
	 * @return 对齐后的字节码，如果没有需要重命名则返回原始newBytes
	 */
	@SuppressWarnings("ForLoopReplaceableByForEach")
	public static byte[] align(byte[] oldBytes, byte[] newBytes) {
		if (oldBytes == null || oldBytes.length == 0) return newBytes;

		MatchContext ctx = CONTEXT.get();
		ctx.reset();

		ctx.currentClass = scan(oldBytes, ctx, true);
		String newClass = scan(newBytes, ctx, false);
		if (!Objects.equals(ctx.currentClass, newClass)) {
			throw new IllegalArgumentException("New class name does not match old class name: " + newClass + " != " + ctx.currentClass);
		}

		var oldGroups = ctx.oldGroups;
		var newGroups = ctx.newGroups;

		// 提前收集所有新产生的方法名，其唯一使命是在最后生成全新名字时作为防碰撞的“避障集”
		Set<String> existingNewNames = ctx.existingNewNames;
		for (Object v : newGroups.values()) {
			if (!LongObjectMap.isValid(v)) continue;
			@SuppressWarnings("unchecked")
			var newGroup = (List<SyntheticInfo>) v;
			for (SyntheticInfo ni : newGroup) {
				existingNewNames.add(ni.name);
			}
		}

		long[]   ks  = newGroups.keys();
		Object[] vs  = newGroups.values();
		int      cap = newGroups.capacity();

		// 【阶段一】全力抢占和复用旧名，杜绝外部调用的 NoSuchMethodError
		for (int i = 0; i < cap; i++) {
			Object v = vs[i];
			if (!LongObjectMap.isValid(v)) continue;

			@SuppressWarnings("unchecked")
			var newGroup = (List<SyntheticInfo>) v;
			var oldGroup = oldGroups.get(ks[i]);
			if (oldGroup == null) continue;

			int newSize = newGroup.size();
			int oldSize = oldGroup.size();

			// Step 1: 精确 Hash 指纹匹配
			for (int j = 0; j < newSize; j++) {
				SyntheticInfo ni = newGroup.get(j);
				for (int k = 0; k < oldSize; k++) {
					SyntheticInfo oi = oldGroup.get(k);
					if (!oi.matched && ni.hash == oi.hash) {
						if (!ni.name.equals(oi.name)) {
							ctx.renameMap.put(ni.name, oi.name);
						}
						ni.matched = true;
						oi.matched = true;
						ctx.usedOldNames.add(oi.name);
						break;
					}
				}
			}

			// Step 2: 顺序对齐 (解决小改动导致 Hash 变更后的平稳降级)
			int oldPtr = 0;
			for (SyntheticInfo ni : newGroup) {
				if (ni.matched) continue;
				while (oldPtr < oldGroup.size() && ctx.usedOldNames.contains(oldGroup.get(oldPtr).name)) {
					oldPtr++;
				}
				if (oldPtr < oldGroup.size()) {
					SyntheticInfo oi         = oldGroup.get(oldPtr);
					String        targetName = oi.name;

					// 必须强行占用 targetName，新类里原有的同名方法将在阶段二被我们安全重命名
					ctx.renameMap.put(ni.name, targetName);
					ni.matched = true;
					ctx.usedOldNames.add(targetName);

					oldPtr++;
				}
			}
		}

		// 【阶段二】扫尾防线：统一处理全部未匹配的新方法，防止内部重组带来的 ClassFormatError
		int freshId = 0;
		for (int i = 0; i < cap; i++) {
			Object v = vs[i];
			if (!LongObjectMap.isValid(v)) continue;

			@SuppressWarnings("unchecked")
			var newGroup = (List<SyntheticInfo>) v;

			for (SyntheticInfo ni : newGroup) {
				if (!ni.matched) {
					// 检查它的原名是否已经在阶段一被分配给了其他重要逻辑
					if (ctx.usedOldNames.contains(ni.name)) {
						String freshName;
						do {
							freshName = ni.logicalName + (freshId++);
						} while (ctx.usedOldNames.contains(freshName) || existingNewNames.contains(freshName));

						ctx.renameMap.put(ni.name, freshName);
						ctx.usedOldNames.add(freshName);
					} else {
						ctx.usedOldNames.add(ni.name);
					}
				}
			}
		}

		return ctx.renameMap.isEmpty() ? newBytes : applyTransform(newBytes, ctx);
	}
	//endregion

	//region 字节码转换+扫描
	/**
	 * 应用转换规则到字节码
	 * @param bytes 原始字节码
	 * @param ctx   匹配上下文
	 * @return 转换后的字节码
	 */
	private static byte[] applyTransform(byte[] bytes, LambdaAligner.MatchContext ctx) {
		ClassReader cr = new ClassReader(bytes);
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);

		Remapper remapper = new Remapper(Opcodes.ASM9) {
			/**
			 * 处理合成方法的定义和 Handle 引用
			 * 这是对齐 Lambda 的核心
			 */
			@Override
			public String mapMethodName(String owner, String name, String desc) {
				if (owner.equals(ctx.currentClass)) {
					if (ctx.renameMap.containsKey(name)) {
						return ctx.renameMap.get(name);
					}
				}
				return name;
			}

			/** 处理 $deserializeLambda$ 内部的方法名字符串常量 */
			@Override
			public Object mapValue(Object value) {
				if (value instanceof String s && s.length() > LAMBDA_LENGTH) {
					if (ctx.renameMap.containsKey(s)) { // 快速预过滤 lambda$ 或 access$
						return ctx.renameMap.get(s);
					}
				}
				return super.mapValue(value);
			}
		};

		cr.accept(new ClassRemapper(cw, remapper), ClassReader.EXPAND_FRAMES);
		return cw.toByteArray();
	}

	/**
	 * 扫描字节码并收集合成方法信息，并利用了 ASM 顺序解析字节码自动分组
	 * @param bytes 要扫描的字节码
	 * @param ctx   匹配上下文
	 * @param isOld 是否为旧版本
	 * @return 类名
	 */
	private static String scan(byte[] bytes, MatchContext ctx, boolean isOld) {
		var visitor = new ClassVisitor(Opcodes.ASM9) {
			public String className;
			@Override
			public void visit(int version, int access, String name, String sig, String superName, String[] itfs) {
				className = name;
			}

			@Override
			public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] exc) {
				// 构造函数和静态初始化块不处理
				if (name.startsWith("<")) {
					return null;
				}

				boolean isBridge = (acc & Opcodes.ACC_BRIDGE) != 0;
				// 如果是桥接方法，通常由编译器自动管理，且不涉及 lambda$ 这种随机命名问题，建议跳过
				if (isBridge) return null;

				boolean isSynthetic    = (acc & Opcodes.ACC_SYNTHETIC) != 0;
				boolean matchesPattern = isSyntheticName(name);

				if (!isSynthetic && !matchesPattern) {
					return null; // 业务方法（如 aaa）直接跳过，不参与 hash 和 rename 映射
				}

				MethodFingerprinter fingerprinter = ctx.fingerprinter;
				fingerprinter.reset();
				fingerprinter.setContext(className);
				return new MethodVisitor(Opcodes.ASM9, fingerprinter) {
					@Override
					public void visitEnd() {
						String        logicalName = extractLogicalName(name);
						SyntheticInfo info        = ctx.acquireInfo(name, desc, fingerprinter.getHash(), logicalName);
						groupByLogic(ctx, isOld ? ctx.oldGroups : ctx.newGroups, info);
					}
				};
			}
		};
		new ClassReader(bytes).accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return visitor.className;
	}
	//endregion

	//region 辅助方法
	private static boolean isSyntheticName(String name) {
		// 仅针对名称具有随机/递增序号、且逻辑上可能发生偏移的方法
		return name.contains("lambda$")    // Java / Kotlin Indy
		       || name.contains("$lambda")    // Kotlin
		       || name.contains("$anonfun$")  // Scala
		       || name.contains("access$");   // Accessors (内部类访问桩)
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
		// 针对 Scala: $anonfun$main$1 -> $anonfun$main
		// 针对 Java/Kotlin: lambda$main$0 -> lambda$main
		int lastDollar = name.lastIndexOf('$');
		if (lastDollar <= 0) return name;

		// 检查最后一部分是否为纯数字序号
		boolean isNumericSuffix = true;
		for (int i = lastDollar + 1; i < name.length(); i++) {
			if (!Character.isDigit(name.charAt(i))) {
				isNumericSuffix = false;
				break;
			}
		}

		if (isNumericSuffix) {
			return name.substring(0, lastDollar);
		}

		// 如果不是数字结尾（如 $deserializeLambda$），保持原样
		return name;
	}


	/**
	 * 按逻辑名称和描述符对合成方法进行分组
	 * @param ctx    匹配上下文
	 * @param target 存放分组结果的容器
	 * @param info   合成方法信息
	 */
	private static void groupByLogic(
	 MatchContext ctx, LongObjectMap<List<SyntheticInfo>> target, SyntheticInfo info) {
		// 使用逻辑名称+描述符作为分组键
		long key  = Utils.compositeHash(info.logicalName, info.desc);
		var  list = target.get(key);
		if (list == null) {
			list = ctx.acquireList();
			target.put(key, list);
		}
		list.add(info);
	}
	//endregion

	//region 数据结构类
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
		boolean matched;

		SyntheticInfo(String n, String d, long h, String l) {
			update(n, d, h, l);
		}

		void update(String n, String d, long h, String l) {
			name = n;
			desc = d;
			hash = h;
			logicalName = l;
			matched = false;
		}
	}
	//endregion
}