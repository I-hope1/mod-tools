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

	/**
	 * 单个宿主类下匿名类数量的硬上限（§6.3-2）。超过即按 §4.3 拒绝整个宿主组。
	 *
	 * <p><b>为什么不采用"告警并降级为不重命名"</b>：不对齐时，新编译产物的 {@code Foo$2} 与 JVM 中
	 * 已加载的旧 {@code Foo$2} 同名但语义不同，一旦进入重定义就正好是本模块要消灭的"存活实例
	 * 被无关新类占据物理槽位"。因此这里唯一安全的降级是**不对齐**（由调用方整体放弃该宿主组）。</p>
	 *
	 * <p>设为 {@link Integer#MAX_VALUE} 可关闭该上限（仅供诊断）。</p>
	 */
	public static int  MAX_ANON_PER_HOST = 128;

	/**
	 * 对齐流程的软超时（毫秒，§6.3-3）。超时即按 §4.3 拒绝该宿主组。
	 *
	 * <p>比对采用 elapsed 形式（{@code now - start}），因此天然不会溢出；
	 * 设为 {@link Long#MAX_VALUE} 可关闭超时；设为 {@code <= 0} 表示"无预算"，
	 * 在第一个检查点即确定性熔断（便于回归测试，不依赖墙上时钟分辨率）。</p>
	 */
	public static long ALIGN_TIMEOUT_MS  = 2000L;

	/**
	 * 对齐被安全门拒绝（§4.3）。
	 *
	 * <p>语义：<b>宿主类 + 其下属全部匿名类</b>作为一个原子单元整体放弃 —— 调用方不得把其中
	 * 任何一项送入本次重定义（否则宿主新字节码会引用到没被对齐的 {@code Foo$N}）。</p>
	 *
	 * <p>继承 {@link IllegalStateException} 是为了不破坏既有调用方与回归断言
	 * （{@code AnonClassReproTest} Scenario 19 就以 {@code IllegalStateException} 捕获后置校验失败）。</p>
	 */
	public static class AlignmentRejectedException extends IllegalStateException {
		/** 被拒绝的宿主类内部名 */
		public final String hostSlash;
		/** 拒绝原因（会进入 {@code [HOTSWAP-REJECT]} 告警） */
		public final String reason;

		public AlignmentRejectedException(String hostSlash, String reason) {
			super("[ANON_ALIGN_REJECT] " + hostSlash + ": " + reason);
			this.hostSlash = hostSlash;
			this.reason = reason;
		}
	}

	/** 是否已超出软超时窗口。 */
	private static boolean isTimedOut(long startNanos) {
		// 无预算（<= 0）时确定性熔断，而不是依赖"elapsed 是否已 >= 1ms"这种墙上时钟分辨率
		if (ALIGN_TIMEOUT_MS <= 0L) return true;
		return (System.nanoTime() - startNanos) / 1_000_000L > ALIGN_TIMEOUT_MS;
	}

	/** 超时检查点：命中即拒绝宿主组。 */
	private static void checkTimeout(String hostSlash, long startNanos) {
		if (isTimedOut(startNanos)) {
			throw new AlignmentRejectedException(hostSlash,
				"alignment exceeded the " + ALIGN_TIMEOUT_MS + "ms soft timeout (§6.3-3)");
		}
	}

	/** 诊断日志（§6.4 {@code -Dnipx.agent.anon_debug} / 别名 {@code -Dnipx.anonAlign.debug}，或全局 DEBUG）。 */
	private static void dbg(String msg) {
		if (HotSwapAgent.ANON_DEBUG || HotSwapAgent.DEBUG) {
			HotSwapAgent.info("[ANON_ALIGN] " + msg);
		}
	}

	public static class AlignmentStats {
		public int tier1Matches;
		public int tier2Matches;
		public int tier3Matches;
		public int tier4Matches;
		public int tier5Matches;
		public int ambiguousMatches;
		/**
		 * 在**禁止 minDiff 仲裁的层**（Tier 2 / Tier 4）完成"双向唯一"配对后，仍然无法确定性区分的候选对数（§4.3-①）。
		 *
		 * <p>统计口径：剩余新类中拥有 &gt;= 2 个剩余旧候选的个数（1-to-N），加上剩余旧类中拥有 &gt;= 2 个
		 * 剩余新候选的个数（N-to-1）。只要 &gt; 0 就说明该层存在真实歧义 —— 双向唯一配对是贪心且
		 * 完备的，凡能唯一确定的都已被取走，留下的必然是"多对多"。</p>
		 *
		 * <p>非严格模式下这些类退化为"新增/孤儿"；严格模式（{@code -Dnipx.agent.anon_strict=true}）
		 * 下与 {@link #ambiguousMatches} 一起构成"缺乏唯一证据"的完整集合，任一非零即拒绝整个宿主组。</p>
		 */
		public int ambiguousPairs;
		public int newClasses;
		public int orphanClasses;

		@Override
		public String toString() {
			return "AlignmentStats[T1=" + tier1Matches + ", T2=" + tier2Matches +
			       ", T3=" + tier3Matches + ", T4=" + tier4Matches +
			       ", T5=" + tier5Matches + ", ambiguous=" + ambiguousMatches +
			       ", ambiguousPairs=" + ambiguousPairs +
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
		return alignCascading(hostClassName, newHostBytes, oldAnonClasses, newAnonClasses, oldResolver, newResolver);
	}

	/**
	 * 级联树匿名类对齐（Cascading Tree Anonymous Class Aligner）。
	 *
	 * <p>按 {@code $} 嵌套深度组织匿名类树形拓扑（Level 1 -> Level N），自顶向下逐层推进：
	 * <ul>
	 *   <li><b>Level 1</b>：宿主直接子匿名类（如 {@code Foo$1}, {@code Foo$2}），在宿主作用域内对齐；</li>
	 *   <li><b>Level > 1</b>：嵌套匿名类（如 {@code Foo$1$1}），候选作用域严格收敛于其父类对齐映射后的目标旧类所包含的子类集合，物理阻断跨树夺舍；</li>
	 *   <li><b>前缀派生</b>：未匹配新类的类名前缀强制继承其父类重映射后的目标名称，保证 JVM 内部类层级不被破坏；</li>
	 *   <li><b>严格校验</b>：对齐映射结果执行单射性与前缀不变量校验，不符合则阻断提交。</li>
	 * </ul>
	 */
	public static Result alignCascading(
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
		final long   startNanos = System.nanoTime();
		dbg("begin alignment for host " + hostSlash + " (maxPerHost=" + MAX_ANON_PER_HOST + ", timeoutMs=" + ALIGN_TIMEOUT_MS + ", strict=" + HotSwapAgent.ANON_STRICT + ")");

		// 归一化输入 Map 为内部名
		Map<String, byte[]> normOld = normalizeMap(oldAnonClasses, hostSlash);
		Map<String, byte[]> normNew = normalizeMap(newAnonClasses, hostSlash);

		// 构造能够解析宿主类的安全 Resolver，防止默认 fallback 导致宿主方法追溯永远返回 null
		Function<String, byte[]> effectiveOldResolver = oldResolver != null ? oldResolver :
			(name -> {
				if (name.equals(hostSlash)) {
					byte[] b = oldAnonClasses != null ? oldAnonClasses.get(hostSlash) : null;
					if (b != null) return b;
					return newHostBytes;
				}
				return normOld.get(name);
			});

		Function<String, byte[]> effectiveNewResolver = newResolver != null ? newResolver :
			(name -> name.equals(hostSlash) ? newHostBytes : normNew.get(name));

		// 单次解析宿主 ClassNode 供整个流程复用，杜绝多次构建 AST 导致的停顿与 GC 压力
		ClassNode oldHostNode = parseHostNode(hostSlash, effectiveOldResolver);
		ClassNode newHostNode = parseHostNode(hostSlash, effectiveNewResolver);

		// 解析新旧匿名类特征（接入严密准入分类器，并复用宿主节点）
		List<AnonInfo> oldInfos = parseInfos(hostSlash, oldHostNode, normOld, effectiveOldResolver);
		List<AnonInfo> newInfos = parseInfos(hostSlash, newHostNode, normNew, effectiveNewResolver);

		// §6.3-2 数量硬上限： pathological 输入（代码生成产物、巨型 switch 表达式）下
		// O(N^2) 对齐会无提示地变慢，因此这里设硬上限并整体拒绝，而不是"降级为不对齐"（见 MAX_ANON_PER_HOST javadoc）。
		int anonCount = Math.max(oldInfos.size(), newInfos.size());
		if (anonCount > MAX_ANON_PER_HOST) {
			throw new AlignmentRejectedException(hostSlash,
				"anonymous class count " + anonCount + " exceeds MAX_ANON_PER_HOST=" + MAX_ANON_PER_HOST + " (§6.3-2)");
		}
		checkTimeout(hostSlash, startNanos);

		AlignmentStats stats = new AlignmentStats();

		// 按层级（depth of '$'）组织类信息
		Map<Integer, List<AnonInfo>> oldByLevel = new TreeMap<>();
		Map<Integer, List<AnonInfo>> newByLevel = new TreeMap<>();
		for (AnonInfo o : oldInfos) {
			oldByLevel.computeIfAbsent(getHierarchyLevel(hostSlash, o.name), k -> new ArrayList<>()).add(o);
		}
		for (AnonInfo n : newInfos) {
			newByLevel.computeIfAbsent(getHierarchyLevel(hostSlash, n.name), k -> new ArrayList<>()).add(n);
		}

		int maxLevel = 1;
		for (int l : oldByLevel.keySet()) maxLevel = Math.max(maxLevel, l);
		for (int l : newByLevel.keySet()) maxLevel = Math.max(maxLevel, l);
		if (maxLevel > 4) {
			String msg = "anonymous class nesting depth " + maxLevel + " > 4 detected";
			if (HotSwapAgent.ANON_STRICT) {
				throw new AlignmentRejectedException(hostSlash, msg + "; strict mode rejects the host group (§4.3-2)");
			}
			// 注意：这只是一个**诊断阈值**，不是能力边界 —— 层级推进本身与深度无关，
			// 真正的闸门是 MAX_ANON_PER_HOST（§6.3-2）与 ALIGN_TIMEOUT_MS（§6.3-3）。
			// 早期注释曾声称此处"内容哈希退化为 #ANON_relId#"，那是错的：AnonClassHasher.MAX_DEPTH 从不生效（见该类注释）。
			HotSwapAgent.warn("[ANON_ALIGN] " + msg + ". Diagnostic threshold only"
				+ " (cascade is depth-generic; the real guards are MAX_ANON_PER_HOST=" + MAX_ANON_PER_HOST
				+ " and ALIGN_TIMEOUT_MS=" + ALIGN_TIMEOUT_MS + "ms).");
		}

		Map<AnonInfo, AnonInfo> matchedNewToOld = new LinkedHashMap<>();
		Map<String, String> renameMap = new LinkedHashMap<>();
		Set<String> takenTargetNames = new HashSet<>(normOld.keySet());

		// 自顶向下逐层推进（Top-down Cascading Progression）
		for (int level = 1; level <= maxLevel; level++) {
			checkTimeout(hostSlash, startNanos);
			List<AnonInfo> oldLevelInfos = oldByLevel.getOrDefault(level, Collections.emptyList());
			List<AnonInfo> newLevelInfos = newByLevel.getOrDefault(level, Collections.emptyList());
			dbg("level " + level + ": old=" + oldLevelInfos.size() + ", new=" + newLevelInfos.size());

			if (level == 1) {
				// Level 1: 宿主直接子匿名类 (如 Foo$1, Foo$2)
				Map<AnonInfo, AnonInfo> l1Matches = matchHierarchical(oldLevelInfos, newLevelInfos, stats, hostSlash, startNanos);
				for (Map.Entry<AnonInfo, AnonInfo> entry : l1Matches.entrySet()) {
					matchedNewToOld.put(entry.getKey(), entry.getValue());
					renameMap.put(entry.getKey().name, entry.getValue().name);
					dbg("  level 1 matched " + entry.getKey().name + " -> " + entry.getValue().name);
				}
				for (AnonInfo n : newLevelInfos) {
					if (!renameMap.containsKey(n.name)) {
						int idx = 1;
						String candidate;
						do {
							candidate = hostSlash + "$" + idx++;
						} while (takenTargetNames.contains(candidate));
						takenTargetNames.add(candidate);
						renameMap.put(n.name, candidate);
						dbg("  level 1 unmatched " + n.name + " -> new number " + candidate);
					}
				}
			} else {
				// Level > 1: 嵌套匿名类 (如 Foo$1$1, Foo$2$1)
				// 作用域收敛：候选严格限制在已配对的父级类名所对应的旧子级范围内
				Map<String, List<AnonInfo>> newByParent = new LinkedHashMap<>();
				for (AnonInfo n : newLevelInfos) {
					newByParent.computeIfAbsent(getParentName(hostSlash, n.name), k -> new ArrayList<>()).add(n);
				}

				Map<String, List<AnonInfo>> oldByParent = new LinkedHashMap<>();
				for (AnonInfo o : oldLevelInfos) {
					oldByParent.computeIfAbsent(getParentName(hostSlash, o.name), k -> new ArrayList<>()).add(o);
				}

				for (Map.Entry<String, List<AnonInfo>> entry : newByParent.entrySet()) {
					String newParent = entry.getKey();
					List<AnonInfo> newChildren = entry.getValue();
					String targetParent = renameMap.get(newParent);
					if (targetParent == null) {
						targetParent = newParent;
					}
					dbg("  level " + level + " scope " + newParent + " -> " + targetParent + " (new=" + newChildren.size() + ")");

					List<AnonInfo> oldCandidateChildren = oldByParent.getOrDefault(targetParent, Collections.emptyList());
					Map<AnonInfo, AnonInfo> childMatches = matchHierarchical(oldCandidateChildren, newChildren, stats, hostSlash, startNanos);
					for (Map.Entry<AnonInfo, AnonInfo> m : childMatches.entrySet()) {
						matchedNewToOld.put(m.getKey(), m.getValue());
						renameMap.put(m.getKey().name, m.getValue().name);
						dbg("  level " + level + " matched " + m.getKey().name + " -> " + m.getValue().name);
					}

					for (AnonInfo n : newChildren) {
						if (!renameMap.containsKey(n.name)) {
							int idx = 1;
							String candidate;
							do {
								candidate = targetParent + "$" + idx++;
							} while (takenTargetNames.contains(candidate));
							takenTargetNames.add(candidate);
							renameMap.put(n.name, candidate);
							dbg("  level " + level + " unmatched " + n.name + " -> new number " + candidate);
						}
					}
				}
			}
		}

		// 收集孤儿类
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
		dbg("level pass done: " + stats + ", orphans=" + orphanOldClasses);

		// §4.3-① 严格模式：只要本轮存在**未被唯一证据证成**的候选配对，就熔断整个宿主组。
		//
		// 两个计数的含义（合起来才是"无法唯一证明"的完整集合）：
		//   • ambiguousMatches —— 在**允许 minDiff 的层（Tier 1 / Tier 3）**由"距离最近"仲裁出来的配对。
		//     它不是 nondeterministic bug，而是一个**确定性但语义错误**的 tie-breaker：序号偏向
		//     "插在前面的新类"，于是插入类会抢走旧身份（详见 §4.1 注记的实测）。
		//   • ambiguousPairs  —— 在**禁止仲裁的层（Tier 2 / Tier 4）**做完双向唯一配对后仍多对多、
		//     只能退化为"新增/孤儿"的残留。
		//
		// strict 的定位是**安全门，不是匹配策略**：它不改变任何"双向唯一"配对的结论（那些不会计入上面
		// 任一计数），也不改变非 strict 模式下的 minDiff 仲裁行为（那时仍只打 [WARN-ANON]）。
		if (HotSwapAgent.ANON_STRICT) {
			int unproven = stats.ambiguousMatches + stats.ambiguousPairs;
			if (unproven > 0) {
				throw new AlignmentRejectedException(hostSlash,
					"strict mode: " + unproven + " candidate pair(s) lack unique evidence"
					+ " (minDiff-arbitrated=" + stats.ambiguousMatches + " at Tier 1/3,"
					+ " unresolved-after-bi-unique=" + stats.ambiguousPairs + " at Tier 2/4) (§4.3-1)");
			}
		}

		// 后置严格校验 (Validation Invariants)
		validateRenameMap(renameMap, hostSlash);
		dbg("renameMap=" + renameMap);

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

	public static int getHierarchyLevel(String hostSlash, String anonSlash) {
		if (hostSlash == null || anonSlash == null || !anonSlash.startsWith(hostSlash + "$")) return 1;
		String suffix = anonSlash.substring(hostSlash.length() + 1);
		int count = 1;
		for (int i = 0; i < suffix.length(); i++) {
			if (suffix.charAt(i) == '$') count++;
		}
		return count;
	}

	public static String getParentName(String hostSlash, String anonSlash) {
		if (anonSlash == null || hostSlash == null) return hostSlash;
		int lastDollar = anonSlash.lastIndexOf('$');
		if (lastDollar <= hostSlash.length()) return hostSlash;
		return anonSlash.substring(0, lastDollar);
	}

	public static class EnclosingMethodInfo {
		public final String name;
		public final String desc;

		public EnclosingMethodInfo(String name, String desc) {
			this.name = name;
			this.desc = desc;
		}
	}

	public static ClassNode parseHostNode(String hostSlash, Function<String, byte[]> resolver) {
		if (hostSlash == null || resolver == null) return null;
		try {
			byte[] bytes = resolver.apply(hostSlash);
			if (bytes == null) {
				bytes = resolver.apply(hostSlash.replace('/', '.'));
			}
			if (bytes == null) return null;
			ClassNode cn = new ClassNode();
			new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			return cn;
		} catch (Throwable t) {
			return null;
		}
	}

	public static void validateRenameMap(Map<String, String> renameMap, String hostSlash) {
		if (renameMap == null || renameMap.isEmpty()) return;
		Set<String> seenTargets = new HashSet<>();
		for (Map.Entry<String, String> e : renameMap.entrySet()) {
			String src = e.getKey();
			String tgt = e.getValue();
			if (!seenTargets.add(tgt)) {
				throw new AlignmentRejectedException(hostSlash,
					"non-injective mapping: multiple classes map to " + tgt + " [ANON_ALIGN_VALIDATION]");
			}
			String srcParent = getParentName(hostSlash, src);
			String expectedPrefix = renameMap.getOrDefault(srcParent, srcParent);
			if (!tgt.startsWith(expectedPrefix + "$")) {
				throw new AlignmentRejectedException(hostSlash,
					"prefix invariant violated for " + src + " -> " + tgt +
					" (expected prefix: " + expectedPrefix + "$) [ANON_ALIGN_VALIDATION]");
			}
		}
	}

	/**
	 * 严格准入分类器：校验字节码是否为真正的匿名内部类。
	 * <p>综合 JVM 规范的 {@code InnerClasses} 属性（匿名类 innerName 必为 null）、
	 * {@code ACC_ENUM} 排除以及名称模式校验。</p>
	 */
	public static boolean isAnonymousClass(ClassNode cn, String hostClassName) {
		if (cn == null || hostClassName == null) return false;
		if ((cn.access & Opcodes.ACC_ENUM) != 0) return false;
		if (cn.innerClasses != null) {
			for (org.objectweb.asm.tree.InnerClassNode icn : cn.innerClasses) {
				if (cn.name.equals(icn.name)) {
					if (icn.innerName != null) {
						return false;
					}
					break;
				}
			}
		}
		return isAnonymousClassName(hostClassName, cn.name);
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
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			AnonInfo anonInfo = (AnonInfo) o;
			return Objects.equals(name, anonInfo.name);
		}

		@Override
		public int hashCode() {
			return name != null ? name.hashCode() : 0;
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

	public static EnclosingMethodInfo resolveHostMethodForAnon(String hostSlash, ClassNode hostNode, String anonSlash) {
		if (hostNode == null || hostSlash == null || anonSlash == null || hostNode.methods == null) return null;
		try {
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

			// 顺着 indy 和方法调用向上追溯调用链，直到定位到真正的源码宿主方法名与描述符（具备深度上限与死循环保护）
			int depth = 0;
			while (current != null && ++depth <= 32) {
				String norm = normalizeEnclosingMethod(current.name);
				if (norm != null && !norm.equals("null") && !norm.isEmpty() && !norm.startsWith("lambda$")) {
					return new EnclosingMethodInfo(norm, current.desc);
				}
				MethodNode caller = findCallerMethod(hostNode, current.name, current.desc, visited);
				if (caller == null) {
					if (norm != null && !norm.equals("null") && !norm.isEmpty()) {
						return new EnclosingMethodInfo(norm, current.desc);
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

	public static String resolveHostMethodForAnon(String hostSlash, String anonSlash, Function<String, byte[]> resolver) {
		ClassNode hostNode = parseHostNode(hostSlash, resolver);
		EnclosingMethodInfo info = resolveHostMethodForAnon(hostSlash, hostNode, anonSlash);
		return info != null ? info.name : null;
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
	 ClassNode hostNode,
	 Map<String, byte[]> classes,
	 Function<String, byte[]> resolver) {
		List<AnonInfo> list = new ArrayList<>();
		Map<String, Long> hashCache = new HashMap<>();

		for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
			String name = entry.getKey();
			byte[] bytes = entry.getValue();
			if (bytes == null || bytes.length == 0) continue;

			ClassNode cn = new ClassNode();
			// 优化：parseInfos 仅需读取类结构签名与属性，使用 SKIP_CODE 避免全量解析指令体
			new ClassReader(bytes).accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			if (!isAnonymousClass(cn, hostSlash)) continue;

			Long hash = AnonClassHasher.hash(name, bytes, hostSlash, resolver, hashCache, null, 0);

			String superName = cn.superName != null ? cn.superName : "java/lang/Object";
			List<String> interfaces = cn.interfaces != null ? new ArrayList<>(cn.interfaces) : new ArrayList<>();
			Collections.sort(interfaces);

			String outerMethod = normalizeEnclosingMethod(cn.outerMethod);
			String outerMethodDesc = cn.outerMethodDesc;
			// 针对 javac 8 的嵌套 lambda 缺陷（EnclosingMethod 生成虚拟的 lambda$null$0）：
			// 通过扫描已解析的宿主类 ClassNode 恢复其真实外层源码方法与描述符
			if ((outerMethod == null || "null".equals(outerMethod)) && hostNode != null) {
				EnclosingMethodInfo hostMethodInfo = resolveHostMethodForAnon(hostSlash, hostNode, name);
				if (hostMethodInfo != null) {
					outerMethod = hostMethodInfo.name;
					outerMethodDesc = hostMethodInfo.desc;
				}
			}

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

	private static Map<AnonInfo, AnonInfo> matchHierarchical(
	 List<AnonInfo> oldList, List<AnonInfo> newList, AlignmentStats stats,
	 String hostSlash, long startNanos) {
		Map<AnonInfo, AnonInfo> matchedNewToOld = new LinkedHashMap<>();
		List<AnonInfo> oldToUse = new ArrayList<>(oldList);
		List<AnonInfo> newToUse = new ArrayList<>(newList);
		if (TEST_REVERSE_ORDER) {
			Collections.reverse(oldToUse);
			Collections.reverse(newToUse);
		}

		Set<AnonInfo> remainingOld = new LinkedHashSet<>(oldToUse);
		Set<AnonInfo> remainingNew = new LinkedHashSet<>(newToUse);

		// Tier 1: 内容哈希精确相同 + 宿主方法相同 (允许 minDiff 仲裁，且强制要求 contentHash != null)
		matchTier(1, remainingNew, remainingOld, matchedNewToOld, stats, (n, o) ->
		 n.contentHash != null
		  && Objects.equals(n.contentHash, o.contentHash)
		  && Objects.equals(n.outerMethod, o.outerMethod)
		  && Objects.equals(n.outerMethodDesc, o.outerMethodDesc),
		 true, hostSlash, startNanos
		);

		// Tier 2: 内容哈希全局精确相同 (禁止跨方法 minDiff，仅全类唯一孤本采纳，且强制要求 contentHash != null)
		matchTier(2, remainingNew, remainingOld, matchedNewToOld, stats, (n, o) ->
		 n.contentHash != null
		  && Objects.equals(n.contentHash, o.contentHash),
		 false, hostSlash, startNanos
		);

		// Tier 3: 结构签名相同（应对修改方法体导致的哈希变化，允许 minDiff 仲裁）
		// 同宿主方法、同父类、同接口、同字段、同声明方法
		matchTier(3, remainingNew, remainingOld, matchedNewToOld, stats, (n, o) ->
		 Objects.equals(n.outerMethod, o.outerMethod)
		  && Objects.equals(n.outerMethodDesc, o.outerMethodDesc)
		  && Objects.equals(n.superName, o.superName)
		  && Objects.equals(n.interfaces, o.interfaces)
		  && Objects.equals(n.fields, o.fields)
		  && Objects.equals(n.methods, o.methods),
		 true, hostSlash, startNanos
		);

		// Tier 4: 松散结构（同宿主方法 + 同基类与接口，禁止多候选 minDiff 盲猜，直接拒绝）
		matchTier(4, remainingNew, remainingOld, matchedNewToOld, stats, (n, o) ->
		 Objects.equals(n.outerMethod, o.outerMethod)
		  && Objects.equals(n.outerMethodDesc, o.outerMethodDesc)
		  && Objects.equals(n.superName, o.superName)
		  && Objects.equals(n.interfaces, o.interfaces),
		 false, hostSlash, startNanos
		);

		// 注意：坚决移除旧版 Tier 5（按物理类名盲配）。若前 4 层均未匹配，
		// 表明该类为新增类或旧类已删除，严格作为孤儿类保留或新类生成新编号，杜绝内存篡夺。

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
	 MatchPredicate predicate,
	 boolean allowMinDiff,
	 String hostSlash,
	 long startNanos) {
		if (remainingNew.isEmpty() || remainingOld.isEmpty()) return;

		Map<AnonInfo, List<AnonInfo>> newToOld = new LinkedHashMap<>();
		Map<AnonInfo, List<AnonInfo>> oldToNew = new LinkedHashMap<>();
		for (AnonInfo n : remainingNew) {
			// O(N^2) 的主体就在这里，超时检查点放在外层循环即可覆盖绝大部分耗时
			checkTimeout(hostSlash, startNanos);
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

		if (!allowMinDiff) {
			// 低置信层（Tier 4）不允许 minDiff 盲猜：统计"仍有 >= 2 个候选"的歧义对，
			// 供 §4.3-① 的 strict 熔断与诊断使用。非严格模式下这些类退化为新增/孤儿。
			if (stats != null) {
				stats.ambiguousPairs += countAmbiguous(remainingNew, remainingOld, newToOld, oldToNew);
			}
			return;
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

	/**
	 * 统计"禁止 minDiff 的层"完成双向唯一配对后，仍无法确定性区分的候选对数量（§4.3-①）。
	 *
	 * <p>口径：剩余新类中拥有 &gt;= 2 个剩余旧候选的个数（1-to-N），加上剩余旧类中拥有 &gt;= 2 个
	 * 剩余新候选的个数（N-to-1）。只要 &gt; 0 就说明该层存在真实歧义 —— 双向唯一配对是贪心且
	 * 完备的，凡能唯一确定的都已被取走，留下的必然是"多对多"。</p>
	 */
	private static int countAmbiguous(
	 Set<AnonInfo> remainingNew,
	 Set<AnonInfo> remainingOld,
	 Map<AnonInfo, List<AnonInfo>> newToOld,
	 Map<AnonInfo, List<AnonInfo>> oldToNew) {
		int count = 0;
		for (AnonInfo n : remainingNew) {
			List<AnonInfo> candidates = newToOld.get(n);
			if (candidates == null) continue;
			int live = 0;
			for (AnonInfo o : candidates) {
				if (remainingOld.contains(o)) live++;
			}
			if (live >= 2) count++;
		}
		for (AnonInfo o : remainingOld) {
			List<AnonInfo> candidates = oldToNew.get(o);
			if (candidates == null) continue;
			int live = 0;
			for (AnonInfo n : candidates) {
				if (remainingNew.contains(n)) live++;
			}
			if (live >= 2) count++;
		}
		return count;
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
