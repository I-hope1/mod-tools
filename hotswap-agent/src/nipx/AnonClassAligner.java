package nipx;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.*;
import java.util.function.Function;

/**
 * 匿名类对齐器 (Anonymous Class Aligner)。
 *
 * <p>在 DCEVM / JBR 增强重定义环境下，匿名内部类编号按源码出现顺序生成（{@code Foo$1}, {@code Foo$2} ...）。
 * 当在前部插入、删除、重排匿名类时，编译产物的编号发生位移，导致 DCEVM 将 JVM 中已存活的旧实例
 * 物理迁移到内容完全不同的新类上，造成静默内存污染与错配。
 *
 * <p>本对齐器作为纯字节码函数，通过多级特征比对建立新旧匿名类的身份对应关系：
 * <ol>
 *   <li><b>Tier 1 (内容哈希 + 宿主方法)</b>：{@link AnonClassHasher} 内容哈希与所在宿主方法均精确相同；</li>
 *   <li><b>Tier 2 (全局内容哈希)</b>：跨方法或初始化块中内容哈希精确唯一匹配；</li>
 *   <li><b>Tier 3 (结构签名)</b>：同宿主方法、同基类与接口、同字段与方法签名（应对修改方法体导致的哈希漂移）；</li>
 *   <li><b>Tier 4 (松散结构)</b>：同宿主方法、同基类与接口类型；</li>
 *   <li><b>Tier 5 (位置回退)</b>：同名且基类接口相同。</li>
 * </ol>
 *
 * <p>对齐后使用 ASM {@link ClassRemapper} 对新匿名类字节码及宿主类中的所有引用（包括字节码指令、
 * {@code InnerClasses}、{@code EnclosingMethod}、{@code NestHost}/{@code NestMembers}）
 * 进行一致性重命名，未被匹配的旧类作为孤儿在 JVM 中保留不动以维护旧实例引用。
 *
 * <p><b>注意事项与已知边界</b>：
 * <ul>
 *   <li><b>运行时依赖</b>：本对齐器及孤儿保留策略依赖 DCEVM / JBR 增强重定义运行时（{@code -XX:+AllowEnhancedClassRedefinition}），
 *       标准 JVM HotSwap 会因 Schema 变化直接拒绝新增/重排匿名类。</li>
 *   <li><b>多 ClassLoader</b>：{@code pendingAlignedClasses} 以全限定类名为键，同名类在不同 ClassLoader 中时仅首个生效。</li>
 *   <li><b>Kotlin 兼容性</b>：Kotlin 匿名对象（{@code object :}）及局部函数存在特定的 {@code @Metadata} 与命名前缀，
 *       当前套件暂未覆盖 Kotlin 真实夹具验证，官方支持目前聚焦于 Java 8 / 17 / 21 编译器产物。</li>
 * </ul>
 */
public final class AnonClassAligner {

	/** 测试钩子：强制反向遍历以验证顺序无关性 */
	public static boolean TEST_REVERSE_ORDER = false;

	public static class AlignmentStats {
		public int tier1Matches;
		public int tier2Matches;
		public int tier3Matches;
		public int tier4Matches;
		public int tier5Matches;
		public int ambiguousMatches;
		public int newClasses;
		public int orphanClasses;

		@Override
		public String toString() {
			return "AlignmentStats[T1=" + tier1Matches + ", T2=" + tier2Matches +
			       ", T3=" + tier3Matches + ", T4=" + tier4Matches +
			       ", T5=" + tier5Matches + ", ambiguous=" + ambiguousMatches +
			       ", new=" + newClasses + ", orphans=" + orphanClasses + "]";
		}
	}

	public static class Result {
		/** 对齐重命名后的宿主类字节码（若输入了 hostBytes） */
		public final byte[] alignedHostBytes;
		/** 对齐重命名后的所有新匿名类：目标对齐内部名 -> 对齐字节码 */
		public final Map<String, byte[]> alignedAnonClasses;
		/** 重命名映射表：原编译内部名 -> 目标对齐内部名 */
		public final Map<String, String> renameMap;
		/** 未被匹配上的旧匿名类（孤儿类内部名），应在 JVM 中保留不动不触发重定义 */
		public final Set<String> orphanOldClasses;
		/** 对齐统计数据 */
		public final AlignmentStats stats;

		public Result(
		 byte[] alignedHostBytes,
		 Map<String, byte[]> alignedAnonClasses,
		 Map<String, String> renameMap,
		 Set<String> orphanOldClasses) {
			this(alignedHostBytes, alignedAnonClasses, renameMap, orphanOldClasses, new AlignmentStats());
		}

		public Result(
		 byte[] alignedHostBytes,
		 Map<String, byte[]> alignedAnonClasses,
		 Map<String, String> renameMap,
		 Set<String> orphanOldClasses,
		 AlignmentStats stats) {
			this.alignedHostBytes = alignedHostBytes;
			this.alignedAnonClasses = Collections.unmodifiableMap(alignedAnonClasses);
			this.renameMap = Collections.unmodifiableMap(renameMap);
			this.orphanOldClasses = Collections.unmodifiableSet(orphanOldClasses);
			this.stats = stats != null ? stats : new AlignmentStats();
		}
	}

	private AnonClassAligner() { }

	/**
	 * 对齐匿名类并重写宿主与匿名类字节码。
	 *
	 * @param hostClassName 宿主外层类类名（支持点分或斜杠格式，如 {@code com/example/Foo}）
	 * @param newHostBytes  新编译的宿主类字节码（可为 null）
	 * @param oldAnonClasses 旧版本匿名类字节码表（内部名或点分名 -> 字节码）
	 * @param newAnonClasses 新编译的匿名类字节码表（内部名或点分名 -> 字节码）
	 * @return 对齐结果 {@link Result}
	 */
	public static Result align(
	 String hostClassName,
	 byte[] newHostBytes,
	 Map<String, byte[]> oldAnonClasses,
	 Map<String, byte[]> newAnonClasses) {
		return align(hostClassName, newHostBytes, oldAnonClasses, newAnonClasses, null, null);
	}

	/**
	 * 对齐匿名类并重写宿主与匿名类字节码（带字节码解析器）。
	 */
	public static Result align(
	 String hostClassName,
	 byte[] newHostBytes,
	 Map<String, byte[]> oldAnonClasses,
	 Map<String, byte[]> newAnonClasses,
	 Function<String, byte[]> oldResolver,
	 Function<String, byte[]> newResolver) {
		if (hostClassName == null) {
			throw new IllegalArgumentException("hostClassName cannot be null");
		}
		final String hostSlash = hostClassName.replace('.', '/');

		// 归一化输入 Map 为内部名
		Map<String, byte[]> normOld = normalizeMap(oldAnonClasses, hostSlash);
		Map<String, byte[]> normNew = normalizeMap(newAnonClasses, hostSlash);

		// 解析新旧匿名类特征
		List<AnonInfo> oldInfos = parseInfos(hostSlash, normOld, oldResolver != null ? oldResolver : normOld::get);
		List<AnonInfo> newInfos = parseInfos(hostSlash, normNew, newResolver != null ? newResolver : normNew::get);

		AlignmentStats stats = new AlignmentStats();

		// 多级匹配
		Map<AnonInfo, AnonInfo> matchedNewToOld = matchHierarchical(oldInfos, newInfos, stats);

		// 构建 renameMap
		Map<String, String> renameMap = new LinkedHashMap<>();
		Set<String> takenTargetNames = new HashSet<>(normOld.keySet());

		// 1. 已匹配项建立映射
		for (Map.Entry<AnonInfo, AnonInfo> entry : matchedNewToOld.entrySet()) {
			AnonInfo n = entry.getKey();
			AnonInfo o = entry.getValue();
			renameMap.put(n.name, o.name);
		}

		// 2. 为未匹配的新匿名类分配不冲突的目标名称
		for (AnonInfo n : newInfos) {
			if (!renameMap.containsKey(n.name)) {
				// 寻找最小未占用的编号：保留父前缀路径（防止多层嵌套 Foo$1$1 被错误打平成一级类 Foo$3）
				int lastDollar = n.name.lastIndexOf('$');
				String prefix = lastDollar > 0 ? n.name.substring(0, lastDollar) : hostSlash;
				// 关键修复（A2）：若父级类已被重命名（例如新 Foo$2 映射回旧 Foo$1），
				// 则未匹配子类的新编号必须基于映射后的父名派生，维持正确的 JVM 层级树
				String mappedParent = renameMap.get(prefix);
				if (mappedParent != null) {
					prefix = mappedParent;
				}
				int idx = 1;
				String candidate;
				do {
					candidate = prefix + "$" + idx;
					idx++;
				} while (takenTargetNames.contains(candidate));
				takenTargetNames.add(candidate);
				renameMap.put(n.name, candidate);
			}
		}

		// 收集旧类孤儿
		Set<String> matchedOldNames = new HashSet<>();
		for (AnonInfo o : matchedNewToOld.values()) {
			matchedOldNames.add(o.name);
		}
		Set<String> orphanOldClasses = new LinkedHashSet<>();
		for (AnonInfo o : oldInfos) {
			if (!matchedOldNames.contains(o.name)) {
				orphanOldClasses.add(o.name);
			}
		}

		stats.newClasses = newInfos.size() - matchedNewToOld.size();
		stats.orphanClasses = orphanOldClasses.size();

		// 应用 ClassRemapper 重写所有新匿名类字节码
		Map<String, byte[]> alignedAnonClasses = new LinkedHashMap<>();
		for (AnonInfo n : newInfos) {
			String targetName = renameMap.get(n.name);
			byte[] remapped = remapClass(n.bytecode, renameMap);
			alignedAnonClasses.put(targetName, remapped);
		}

		// 应用 ClassRemapper 重写宿主类字节码
		byte[] alignedHostBytes = newHostBytes != null ? remapClass(newHostBytes, renameMap) : null;

		return new Result(alignedHostBytes, alignedAnonClasses, renameMap, orphanOldClasses, stats);
	}

	/**
	 * 使用 ASM ClassRemapper 重写单个类的字节码。
	 */
	public static byte[] remapClass(byte[] classBytes, Map<String, String> renameMap) {
		if (classBytes == null || classBytes.length == 0 || renameMap == null || renameMap.isEmpty()) {
			return classBytes;
		}
		boolean hasNonIdentity = false;
		for (Map.Entry<String, String> entry : renameMap.entrySet()) {
			if (!entry.getKey().equals(entry.getValue())) {
				hasNonIdentity = true;
				break;
			}
		}
		if (!hasNonIdentity) {
			return classBytes;
		}

		ClassReader cr = new ClassReader(classBytes);
		ClassWriter cw = new ClassWriter(0);
		ClassRemapper remapper = new ClassRemapper(cw, new SimpleRemapper(renameMap));
		cr.accept(remapper, 0);
		return cw.toByteArray();
	}

	/**
	 * 判断一个类名是否属于宿主类的匿名内部类。
	 */
	public static boolean isAnonymousClassName(String hostClassName, String className) {
		if (hostClassName == null || className == null) return false;
		String host = hostClassName.replace('.', '/');
		String cls = className.replace('.', '/');
		if (!cls.startsWith(host + "$")) return false;
		String suffix = cls.substring(host.length() + 1);
		if (suffix.isEmpty()) return false;
		for (int i = 0; i < suffix.length(); i++) {
			char ch = suffix.charAt(i);
			if (!Character.isDigit(ch) && ch != '$') {
				return false;
			}
		}
		return !suffix.contains("$$") && !suffix.startsWith("$") && !suffix.endsWith("$");
	}

	public static int parseIndex(String hostClassName, String className) {
		if (!isAnonymousClassName(hostClassName, className)) return -1;
		int lastDollar = className.lastIndexOf('$');
		if (lastDollar < 0 || lastDollar + 1 >= className.length()) return -1;
		try {
			return Integer.parseInt(className.substring(lastDollar + 1));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	//region Internal Matching Logic

	static class AnonInfo {
		final String name;
		final byte[] bytecode;
		final Long contentHash;
		final String superName;
		final List<String> interfaces;
		final String outerMethod;
		final String outerMethodDesc;
		final List<String> fields;
		final List<String> methods;
		final int orderIndex;

		AnonInfo(
		 String name,
		 byte[] bytecode,
		 Long contentHash,
		 String superName,
		 List<String> interfaces,
		 String outerMethod,
		 String outerMethodDesc,
		 List<String> fields,
		 List<String> methods,
		 int orderIndex) {
			this.name = name;
			this.bytecode = bytecode;
			this.contentHash = contentHash;
			this.superName = superName;
			this.interfaces = interfaces;
			this.outerMethod = outerMethod;
			this.outerMethodDesc = outerMethodDesc;
			this.fields = fields;
			this.methods = methods;
			this.orderIndex = orderIndex;
		}

		@Override
		public String toString() {
			return name + "(#hash=" + contentHash + ", outer=" + outerMethod + ", order=" + orderIndex + ")";
		}
	}

	private static Map<String, byte[]> normalizeMap(Map<String, byte[]> input, String hostSlash) {
		Map<String, byte[]> result = new LinkedHashMap<>();
		if (input == null) return result;
		for (Map.Entry<String, byte[]> entry : input.entrySet()) {
			String name = entry.getKey().replace('.', '/');
			if (isAnonymousClassName(hostSlash, name)) {
				result.put(name, entry.getValue());
			}
		}
		return result;
	}

	public static String normalizeEnclosingMethod(String outerMethod) {
		if (outerMethod == null) return null;
		// Kotlin lambda naming: foo$lambda$0 or foo$lambda-0
		int kLambdaIdx = outerMethod.indexOf("$lambda");
		if (kLambdaIdx > 0) {
			return outerMethod.substring(0, kLambdaIdx);
		}
		if (outerMethod.startsWith("lambda$")) {
			// 在 JDK 8 中，lambda 体内的匿名类其 EnclosingMethod 指向 lambda$foo$0，
			// 在 JDK 17/21 中则直接指向外层源码方法 foo。
			// 统一规约为源码方法名，抹除 lambda 编号位移与跨 JDK 差异。
			int lastDollar = outerMethod.lastIndexOf('$');
			if (lastDollar > 7) {
				String suffix = outerMethod.substring(lastDollar + 1);
				boolean allDigits = true;
				for (int i = 0; i < suffix.length(); i++) {
					if (!Character.isDigit(suffix.charAt(i))) { allDigits = false; break; }
				}
				if (allDigits) {
					return outerMethod.substring(7, lastDollar);
				}
			}
			return outerMethod.substring(7);
		}
		return outerMethod;
	}

	public static String resolveHostMethodForAnon(String hostSlash, String anonSlash, Function<String, byte[]> resolver) {
		if (resolver == null || hostSlash == null || anonSlash == null) return null;
		try {
			byte[] hostBytes = resolver.apply(hostSlash);
			if (hostBytes == null) {
				hostBytes = resolver.apply(hostSlash.replace('/', '.'));
			}
			if (hostBytes == null) return null;

			ClassNode hostNode = new ClassNode();
			new ClassReader(hostBytes).accept(hostNode, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			if (hostNode.methods == null) return null;

			List<MethodNode> instantiators = new ArrayList<>();
			for (MethodNode mn : hostNode.methods) {
				if (mn.instructions == null) continue;
				for (org.objectweb.asm.tree.AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn.getOpcode() == Opcodes.NEW && insn instanceof org.objectweb.asm.tree.TypeInsnNode) {
						org.objectweb.asm.tree.TypeInsnNode tin = (org.objectweb.asm.tree.TypeInsnNode) insn;
						if (anonSlash.equals(tin.desc)) {
							instantiators.add(mn);
							break;
						}
					}
				}
			}

			if (instantiators.isEmpty()) {
				HotSwapAgent.warn("[ANON_ALIGN] Could not find instantiator method in host " + hostSlash + " for anonymous class " + anonSlash);
				return null;
			}
			if (instantiators.size() > 1) {
				HotSwapAgent.warn("[ANON_ALIGN] Multiple instantiator methods found for " + anonSlash + ": " + instantiators.size() + " candidates. Picking first.");
			}

			MethodNode current = instantiators.get(0);
			Set<String> visited = new HashSet<>();
			visited.add(current.name + ":" + current.desc);

			// 顺着 indy 和方法调用向上追溯调用链，直到定位到真正的源码宿主方法名（具备深度上限与死循环保护）
			int depth = 0;
			while (current != null && ++depth <= 32) {
				String norm = normalizeEnclosingMethod(current.name);
				if (norm != null && !norm.equals("null") && !norm.isEmpty() && !norm.startsWith("lambda$")) {
					return norm;
				}
				MethodNode caller = findCallerMethod(hostNode, current.name, current.desc, visited);
				if (caller == null) {
					if (norm != null && !norm.equals("null") && !norm.isEmpty()) {
						return norm;
					}
					break;
				}
				visited.add(caller.name + ":" + caller.desc);
				current = caller;
			}
			HotSwapAgent.warn("[ANON_ALIGN] Failed to trace call chain to source method for " + anonSlash + " (bottom=" + instantiators.get(0).name + ")");
		} catch (Throwable ignored) { }
		return null;
	}

	private static MethodNode findCallerMethod(ClassNode hostNode, String calleeMethodName, String calleeMethodDesc, Set<String> visited) {
		for (MethodNode mn : hostNode.methods) {
			if (visited.contains(mn.name + ":" + mn.desc) || mn.instructions == null) continue;
			for (org.objectweb.asm.tree.AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy) {
					for (Object bsmArg : indy.bsmArgs) {
						if (bsmArg instanceof org.objectweb.asm.Handle h) {
							if (hostNode.name.equals(h.getOwner()) && calleeMethodName.equals(h.getName())
							    && (calleeMethodDesc == null || calleeMethodDesc.equals(h.getDesc()))) {
								return mn;
							}
						}
					}
				} else if (insn instanceof org.objectweb.asm.tree.MethodInsnNode min) {
					if (hostNode.name.equals(min.owner) && calleeMethodName.equals(min.name)
					    && (calleeMethodDesc == null || calleeMethodDesc.equals(min.desc))) {
						return mn;
					}
				}
			}
		}
		return null;
	}

	private static List<AnonInfo> parseInfos(
	 String hostSlash,
	 Map<String, byte[]> classes,
	 Function<String, byte[]> resolver) {
		List<AnonInfo> list = new ArrayList<>();
		Map<String, Long> hashCache = new HashMap<>();

		for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
			String name = entry.getKey();
			byte[] bytes = entry.getValue();
			if (bytes == null || bytes.length == 0) continue;

			ClassNode cn = new ClassNode();
			new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

			Long hash = AnonClassHasher.hash(name, bytes, hostSlash, resolver, hashCache, null, 0);

			String superName = cn.superName != null ? cn.superName : "java/lang/Object";
			List<String> interfaces = cn.interfaces != null ? new ArrayList<>(cn.interfaces) : new ArrayList<>();
			Collections.sort(interfaces);

			String outerMethod = normalizeEnclosingMethod(cn.outerMethod);
			// 针对 javac 8 的嵌套 lambda 缺陷（EnclosingMethod 生成虚拟的 lambda$null$0）：
			// 通过扫描宿主类字节码中实例化该匿名类的真实方法恢复其真实外层源码方法
			if ((outerMethod == null || "null".equals(outerMethod)) && resolver != null) {
				String hostMethod = resolveHostMethodForAnon(hostSlash, name, resolver);
				if (hostMethod != null) {
					outerMethod = hostMethod;
				}
			}
			String outerMethodDesc = cn.outerMethodDesc;

			List<String> fields = new ArrayList<>();
			if (cn.fields != null) {
				for (FieldNode fn : cn.fields) {
					fields.add(fn.name + ":" + fn.desc);
				}
				Collections.sort(fields);
			}

			List<String> methods = new ArrayList<>();
			if (cn.methods != null) {
				for (MethodNode mn : cn.methods) {
					// 排除合成方法
					if ((mn.access & Opcodes.ACC_SYNTHETIC) == 0) {
						methods.add(mn.name + ":" + mn.desc);
					}
				}
				Collections.sort(methods);
			}

			int orderIdx = parseIndex(hostSlash, name);
			list.add(new AnonInfo(
			 name, bytes, hash, superName, interfaces,
			 outerMethod, outerMethodDesc, fields, methods, orderIdx
			));
		}

		// 按 orderIndex 排序
		list.sort(Comparator.comparingInt(a -> a.orderIndex));
		return list;
	}

	private static Map<AnonInfo, AnonInfo> matchHierarchical(List<AnonInfo> oldList, List<AnonInfo> newList, AlignmentStats stats) {
		Map<AnonInfo, AnonInfo> matchedNewToOld = new LinkedHashMap<>();
		List<AnonInfo> oldToUse = new ArrayList<>(oldList);
		List<AnonInfo> newToUse = new ArrayList<>(newList);
		if (TEST_REVERSE_ORDER) {
			Collections.reverse(oldToUse);
			Collections.reverse(newToUse);
		}

		Set<AnonInfo> remainingOld = new LinkedHashSet<>(oldToUse);
		Set<AnonInfo> remainingNew = new LinkedHashSet<>(newToUse);

		// Tier 1: 内容哈希精确相同 + 宿主方法相同
		matchTier(1, remainingNew, remainingOld, matchedNewToOld, stats, (n, o) ->
		 Objects.equals(n.contentHash, o.contentHash)
		  && Objects.equals(n.outerMethod, o.outerMethod)
		  && Objects.equals(n.outerMethodDesc, o.outerMethodDesc)
		);

		// Tier 2: 内容哈希全局精确相同
		matchTier(2, remainingNew, remainingOld, matchedNewToOld, stats, (n, o) ->
		 Objects.equals(n.contentHash, o.contentHash)
		);

		// Tier 3: 结构签名相同（应对修改方法体导致的哈希变化）
		// 同宿主方法、同父类、同接口、同字段、同声明方法
		matchTier(3, remainingNew, remainingOld, matchedNewToOld, stats, (n, o) ->
		 Objects.equals(n.outerMethod, o.outerMethod)
		  && Objects.equals(n.outerMethodDesc, o.outerMethodDesc)
		  && Objects.equals(n.superName, o.superName)
		  && Objects.equals(n.interfaces, o.interfaces)
		  && Objects.equals(n.fields, o.fields)
		  && Objects.equals(n.methods, o.methods)
		);

		// Tier 4: 松散结构（同宿主方法 + 同基类与接口）
		matchTier(4, remainingNew, remainingOld, matchedNewToOld, stats, (n, o) ->
		 Objects.equals(n.outerMethod, o.outerMethod)
		  && Objects.equals(n.outerMethodDesc, o.outerMethodDesc)
		  && Objects.equals(n.superName, o.superName)
		  && Objects.equals(n.interfaces, o.interfaces)
		);

		// Tier 5: 位置回退（同名且同基类接口）
		matchTier(5, remainingNew, remainingOld, matchedNewToOld, stats, (n, o) ->
		 Objects.equals(n.name, o.name)
		  && Objects.equals(n.superName, o.superName)
		  && Objects.equals(n.interfaces, o.interfaces)
		);

		return matchedNewToOld;
	}

	@FunctionalInterface
	private interface MatchPredicate {
		boolean test(AnonInfo n, AnonInfo o);
	}

	private static class CandidatePair {
		final AnonInfo n;
		final AnonInfo o;
		final int diff;

		CandidatePair(AnonInfo n, AnonInfo o) {
			this.n = n;
			this.o = o;
			this.diff = Math.abs(n.orderIndex - o.orderIndex);
		}
	}

	private static final Comparator<CandidatePair> PAIR_COMPARATOR = (p1, p2) -> {
		int cmp = Integer.compare(p1.diff, p2.diff);
		if (cmp != 0) return cmp;
		cmp = Integer.compare(p1.n.orderIndex, p2.n.orderIndex);
		if (cmp != 0) return cmp;
		cmp = Integer.compare(p1.o.orderIndex, p2.o.orderIndex);
		if (cmp != 0) return cmp;
		cmp = p1.n.name.compareTo(p2.n.name);
		if (cmp != 0) return cmp;
		return p1.o.name.compareTo(p2.o.name);
	};

	private static void matchTier(
	 int tier,
	 Set<AnonInfo> remainingNew,
	 Set<AnonInfo> remainingOld,
	 Map<AnonInfo, AnonInfo> matchedNewToOld,
	 AlignmentStats stats,
	 MatchPredicate predicate) {
		if (remainingNew.isEmpty() || remainingOld.isEmpty()) return;

		Map<AnonInfo, List<AnonInfo>> newToOld = new LinkedHashMap<>();
		Map<AnonInfo, List<AnonInfo>> oldToNew = new LinkedHashMap<>();
		for (AnonInfo n : remainingNew) {
			for (AnonInfo o : remainingOld) {
				if (predicate.test(n, o)) {
					newToOld.computeIfAbsent(n, k -> new ArrayList<>()).add(o);
					oldToNew.computeIfAbsent(o, k -> new ArrayList<>()).add(n);
				}
			}
		}

		if (newToOld.isEmpty()) return;

		// 第一趟：双向互为唯一候选（Bi-directional Unique Matching）
		// 消除单向唯一误判导致的遍历顺序依赖
		List<AnonInfo> uniqueNew = new ArrayList<>();
		List<AnonInfo> uniqueOld = new ArrayList<>();
		for (Map.Entry<AnonInfo, List<AnonInfo>> entry : newToOld.entrySet()) {
			AnonInfo n = entry.getKey();
			List<AnonInfo> oldList = entry.getValue();
			if (oldList.size() == 1) {
				AnonInfo o = oldList.get(0);
				List<AnonInfo> newList = oldToNew.get(o);
				if (newList != null && newList.size() == 1) {
					uniqueNew.add(n);
					uniqueOld.add(o);
				}
			}
		}

		for (int i = 0; i < uniqueNew.size(); i++) {
			AnonInfo n = uniqueNew.get(i);
			AnonInfo o = uniqueOld.get(i);
			matchedNewToOld.put(n, o);
			remainingOld.remove(o);
			remainingNew.remove(n);
			recordTierMatch(stats, tier);
			if (HotSwapAgent.DEBUG || tier >= 4) {
				HotSwapAgent.info("[ANON_MATCH] Tier " + tier + " bi-unique paired: " + n.name + " -> " + o.name);
			}
		}

		if (remainingNew.isEmpty() || remainingOld.isEmpty()) return;

		// 第二趟：存在 1-to-N 或 N-to-1 歧义候选，按 minDiff 绝对确定性仲裁（具备完全的顺序无关性）
		List<CandidatePair> conflictPairs = new ArrayList<>();
		for (AnonInfo n : remainingNew) {
			List<AnonInfo> oldList = newToOld.get(n);
			if (oldList == null) continue;
			for (AnonInfo o : oldList) {
				if (remainingOld.contains(o)) {
					conflictPairs.add(new CandidatePair(n, o));
				}
			}
		}

		if (conflictPairs.isEmpty()) return;
		conflictPairs.sort(PAIR_COMPARATOR);

		Set<AnonInfo> usedNew = new HashSet<>();
		Set<AnonInfo> usedOld = new HashSet<>();
		for (CandidatePair pair : conflictPairs) {
			if (usedNew.contains(pair.n) || usedOld.contains(pair.o)) continue;
			if (!remainingNew.contains(pair.n) || !remainingOld.contains(pair.o)) continue;

			usedNew.add(pair.n);
			usedOld.add(pair.o);
			matchedNewToOld.put(pair.n, pair.o);
			remainingNew.remove(pair.n);
			remainingOld.remove(pair.o);

			stats.ambiguousMatches++;
			recordTierMatch(stats, tier);
			System.err.println("[WARN-ANON] Ambiguous anonymous class match in Tier " + tier +
			                   " resolved by minDiff: " + pair.n.name + " -> " + pair.o.name +
			                   " (diff=" + pair.diff + ")");
			if (HotSwapAgent.DEBUG || tier >= 4) {
				HotSwapAgent.info("[ANON_MATCH] Tier " + tier + " fallback paired (diff=" + pair.diff + "): " + pair.n.name + " -> " + pair.o.name);
			}
		}
	}

	private static void recordTierMatch(AlignmentStats stats, int tier) {
		if (stats == null) return;
		switch (tier) {
			case 1: stats.tier1Matches++; break;
			case 2: stats.tier2Matches++; break;
			case 3: stats.tier3Matches++; break;
			case 4: stats.tier4Matches++; break;
			case 5: stats.tier5Matches++; break;
		}
	}

	//endregion
}
