package nipx;

import nipx.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Lambda 表达式对齐工具类。
 *
 * <p>设计目标：优先保证“不崩溃”，尽可能保证“逻辑严丝合缝”。</p>
 *
 * <p>主要功能：</p>
 * <ol>
 *   <li>分析新旧字节码中的合成方法（synthetic methods）。</li>
 *   <li>通过哈希匹配和顺序对齐算法，建立方法名映射关系。</li>
 *   <li>重写新字节码中的方法名引用，包括 {@code $deserializeLambda$} 里
 *       {@code String.hashCode()} 分派的 {@code lookupswitch} 常量。</li>
 *   <li>解决热交换时因 Lambda 名称变化导致的 {@link NoSuchMethodError}。</li>
 *   <li><b>孤儿 Lambda 智能自适应分流（{@link OrphanPolicy#SMART_ADAPTIVE}）：</b><br>
 *       对于在新版本中被彻底删除的“孤儿方法”，生成带调用栈探测的空壳方法：
 *       <ul>
 *         <li>若调用栈由 {@link nipx.ref.UpdateRef} 发起（UI / 定时轮询 / 事件监听）：主动抛出 {@link NoSuchMethodError}，
 *             驱动 {@code UpdateRef} 执行精准局部熔断（如注销回调 {@code el.update(null)}），彻底根除 60FPS 刷屏空转；</li>
 *         <li>若由普通业务代码发起（未受 UpdateRef 保护）：静默降级返回类型默认值（0 / null / false），绝不引发程序崩溃。</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p><b>调用约定（重要）：</b>{@link #align} 的 {@code oldBytes} 必须是
 * <i>上一轮对齐并注入幽灵方法之后、JVM 里实际生效的字节码</i>，而不是原始编译
 * 产物。若每次热更都拿新版原始 class 作基准，会出现“名字错位”（v2 里跑的是
 * 对齐后的名字，对齐基准却是 v2 编译出的名字），第三次热更会重新触发
 * {@link NoSuchMethodError}；同时上一轮注入的空壳不在原始产物里，也会被漏掉。</p>
 *
 * <p><b>失败降级：</b>整个 {@link #align} 过程被一层 try/catch 包住。若因输入
 * 不合法、ASM 内部异常等任何 {@code Exception} 导致对齐失败，会记录一条日志并
 * 返回原始 {@code newBytes}（降级为“不崩溃”）。这意味着热更成功但老 CallSite
 * 可能因名字未对齐而失效——这是与“不崩溃”的设计目标一致的取舍。</p>
 *
 * <p><b>已知限制：幽灵方法指纹退化。</b> 幽灵方法（空壳）的字节码与原始方法体
 * 不同，其指纹在下一轮对齐中已不是原始指纹。若开发者在 V2 删除了某个 lambda，
 * V3 又把它加回来，V3 的新方法会因指纹失配而走 Step 2 顺序回退——通常仍能
 * 保住名字，但若同组内有其它新增方法，可能被次优先级抢占，引发不必要的重命名。
 * 修复需要给幽灵方法打标记（自定义 attribute 或特殊 access 位），代价与收益
 * 暂不匹配，暂列为已知限制。</p>
 *
 * <p><b>已知限制：改变嵌套拓扑的编辑会丢失身份。</b>
 * {@link #sameNestingLevel} 在 Step 1/2 全程是<b>硬否决</b>：形状不等的候选永远不配对。
 * 因此<b>任何改变嵌套拓扑的编辑</b>——在现有 lambda 体内新增或删除一层内层 lambda——
 * 都会让该 lambda 的 shape 变化，从而失去身份：旧名被幽灵化、新方法去阶段二拿新名。
 * 这是"安全优先于召回"的自觉取舍（形状混淆会让父子错配、语义静默对调，代价更大），
 * 但这类编辑在日常开发中很常见，因此明确记录在此。</p>
 *
 * <p><b>可见性假设：</b>注入的幽灵桩通过 {@code INVOKESTATIC} 调用
 * {@link #onOrphanInvoked(String)}，因此目标类的类加载器必须能看到 {@code nipx.LambdaAligner}。
 * 父委派正常时成立。</p>
 *
 * @see nipx.ref.UpdateRef
 * @see nipx.LambdaRef
 * @see nipx.HotSwapAgent
 */
public class LambdaAligner {

	public static final ThreadLocal<MatchContext> CONTEXT = ThreadLocal.withInitial(MatchContext::new);

	/**
	 * <b>测试钩子</b>：反转分组遍历顺序，用于检测"结果依赖遍历顺序"的缺陷。
	 *
	 * <p>本对齐器的结果**不应**依赖 {@code LongObjectMap} 的遍历顺序（那个顺序对同一输入
	 * 是确定的，但会随输入变化而改变，因此不能作为正确性的依赖）。开启后所有分组遍历
	 * 反向进行；若结果与正序不同，就说明存在隐性的顺序依赖。</p>
	 *
	 * <p>生产环境保持 {@code false}。</p>
	 */
	public static volatile boolean TEST_REVERSE_GROUP_ORDER = false;

	/**
	 * 最近一次 align 的配对来源统计（由证据驱动的指纹配对 vs A/B 趟降级配对）。
	 *
	 * <p><b>警告：全进程共享静态变量，仅供单线程测试断言使用，生产业务逻辑严禁引用。</b></p>
	 */
	public static final class AlignmentStats {
		/** 通过同组 Step 1a 或跨组指纹证据完成配对的数量。 */
		public final int step1Pairs;
		/** 通过 Step 2 A 趟（同上行深度 + 同 shape）完成配对的数量。 */
		public final int passAPairs;
		/** 通过 Step 2 B 趟（放宽条件兜底）完成配对的数量。 */
		public final int passBPairs;

		public AlignmentStats(int step1Pairs, int passAPairs, int passBPairs) {
			this.step1Pairs = step1Pairs;
			this.passAPairs = passAPairs;
			this.passBPairs = passBPairs;
		}

		@Override
		public String toString() {
			return "Stats{step1=" + step1Pairs + ", passA=" + passAPairs + ", passB=" + passBPairs + "}";
		}
	}

	/** 仅供单线程测试断言读取的最近一次对齐统计。测试调用前请先置 null。 */
	public static volatile AlignmentStats LAST_STATS;

	/**
	 * 诊断开关：打开后打印配对决策的细节（谁在哪个阶段拿了哪个旧名字）。
	 *
	 * <p><b>为什么做成常设开关</b>：排查本对齐器的顺序问题时，临时插
	 * 排查本对齐器的顺序问题时，临时插日志打印是必需的；但临时探针有两个反复出现的代价 ——
	 * 忘了移除（污染生产日志），以及 <b>改了没生效却察觉不到</b>
	 * （曾因 hstest 加载陈旧 jar，数轮结论都建立在旧字节码上）。
	 * 做成开关后，排查只需设属性/环境变量，<b>不必改代码</b>。</p>
	 *
	 * <p>开启方式（任一）：</p>
	 * <pre>
	 *   -Dnipx.lambdaAligner.debug=true          系统属性
	 *   NIPX_LAMBDA_ALIGNER_DEBUG=1              环境变量
	 *   LambdaAligner.DEBUG = true               代码/测试直接赋值
	 * </pre>
	 *
	 * <p>生产环境默认关闭。</p>
	 */
	public static volatile boolean DEBUG = initDebug();

	private static boolean initDebug() {
		String p = System.getProperty("nipx.lambdaAligner.debug");
		if (p != null) return Boolean.parseBoolean(p) || "1".equals(p);
		String e = System.getenv("NIPX_LAMBDA_ALIGNER_DEBUG");
		return e != null && (Boolean.parseBoolean(e) || "1".equals(e));
	}

	/** 诊断输出：仅在 {@link #DEBUG} 打开时打印，统一前缀便于 grep。 */
	static void dbg(Supplier<String> msg) {
		if (DEBUG) HotSwapAgent.info("[LambdaAligner] " + msg.get());
	}

	/** 按当前测试钩子决定的方向，在 {@code groups} 上迭代（返回下标序列）。 */
	private static int[] groupOrder(LongObjectMap<?> groups) {
		int n = 0;
		for (int idx = groups.nextEntry(-1); idx != -1; idx = groups.nextEntry(idx)) n++;
		int[] order = new int[n];
		int i = 0;
		for (int idx = groups.nextEntry(-1); idx != -1; idx = groups.nextEntry(idx)) order[i++] = idx;
		if (TEST_REVERSE_GROUP_ORDER) {
			for (int l = 0, r = n - 1; l < r; l++, r--) {
				int t = order[l]; order[l] = order[r]; order[r] = t;
			}
		}
		return order;
	}

	/**
	 * 孤儿 lambda 被复活为空壳时的处理策略。
	 */
	public enum OrphanPolicy {
		/**
		 * 智能自适应策略（<b>推荐且默认</b>）：
		 * <ul>
		 *   <li>若由 {@link nipx.ref.UpdateRef} 发起（UI / 定时轮询 / 事件回调）：抛出 {@link NoSuchMethodError}，
		 *       触发精准局部熔断（如 {@code element.update(null)}），彻底切断 60FPS 空转与死循环刷屏；</li>
		 *   <li>若由普通业务代码发起（未受 UpdateRef 保护）：经 {@link HotSwapAgent#warn} 打印警告并返回默认值（0 / null / false），
		 *       <b>不抛出异常</b>（指幽灵自身不抛；返回的 null / 0 在下游仍可能触发 NPE，需调用方自行判空）。</li>
		 * </ul>
		 */
		SMART_ADAPTIVE,
		/** 经 {@link HotSwapAgent#warn} 打印一条日志，并返回默认值（0 / null / false）。 */
		LOG_AND_RETURN_DEFAULT,
		/** 无条件抛出 {@link NoSuchMethodError}。 */
		THROW_NO_SUCH_METHOD,
		/** 抛出 {@link IllegalStateException}，便于显式暴露问题。 */
		THROW,
		/** 静默返回默认值。 */
		SILENT
	}

	private static volatile OrphanPolicy orphanPolicy = OrphanPolicy.SMART_ADAPTIVE;

	public static void setOrphanPolicy(OrphanPolicy policy) {
		orphanPolicy = Objects.requireNonNull(policy);
	}

	public static OrphanPolicy getOrphanPolicy() {
		return orphanPolicy;
	}

	/**
	 * 清空已记录的孤儿 Lambda 日志去重缓存。
	 */
	public static void clearLoggedOrphans() {
		LOGGED_ORPHANS.clear();
	}

	//region 匹配上下文

	/**
	 * 匹配上下文，存储当前线程的匹配状态。
	 * <p>由 {@link #CONTEXT} 通过 ThreadLocal 持有，在 {@link #align} 的 finally 中清理。</p>
	 */
	public static class MatchContext {
		/**
		 * {@code name + desc -> 新方法名}。
		 * <p>以 desc 为键的一部分，避免同名不同描述符（重载）被一起改名。</p>
		 */
		final Map<String, String> renameMap = new HashMap<>(64);

		/**
		 * {@code name -> 新方法名}，供 {@code mapValue} 改写字符串常量时使用。
		 * <p>仅当该简单名在整个类中见证了唯一且不同于自身的目标时才写入；
		 * 一旦出现歧义，会被移出并记入 {@link #ambiguousNames}。</p>
		 */
		final Map<String, String> renameBySimpleName = new HashMap<>(64);

		/**
		 * 见证表：{@code simpleName -> 唯一见证到的目标名}。
		 * <p>与 {@link #renameBySimpleName} 的区别：这里始终登记，即使
		 * “目标名 == 原名”（保名）。这样“一改一同”的歧义场景才能被识别。</p>
		 */
		final Map<String, String> simpleNameWitness = new HashMap<>(64);

		/**
		 * 出现歧义的简单名集合。
		 * <p>这些名字在 {@code mapValue} 中不再改写字符串常量，宁可保持原样。</p>
		 */
		final Set<String> ambiguousNames = new HashSet<>(16);

		/** 已被占用的旧方法名集合，用于生成 fresh name 时避免碰撞。 */
		final Set<String> usedOldNames = new HashSet<>(64);

		/**
		 * 新类里<b>所有</b>方法的名字（含构造函数、静态块、桥接、普通业务方法、
		 * 合成方法），作为 fresh name 的避障集。由 {@code scan} 无条件填充。</p>
		 */
		final Set<String> existingNewNames = new HashSet<>(64);

		/**
		 * 旧类里<b>所有</b>方法的 {@code name + desc} 集合。既用于冲突检测，
		 * 也防止 fresh name 撞到上一轮注入的幽灵方法。由 {@code scan} 无条件填充。</p>
		 */
		final Set<String> oldNameDescSet = new HashSet<>(64);

		final MethodFingerprinter fingerprinter = MethodFingerprinter.CONTEXT.get();

		/** 当前处理的类名（内部名，形如 {@code com/example/Foo}）。 */
		String currentClass;

		final LongObjectMap<List<SyntheticInfo>> oldGroups = new LongObjectMap<>(128);
		final LongObjectMap<List<SyntheticInfo>> newGroups = new LongObjectMap<>(128);

		/**
		 * 新类里 {@code lambda 方法名 -> 信息} 的索引，供 {@code hasUnmatchedChild}
		 * 判断"我的子 lambda 是否还没落定"。由 {@code scan} 填充。
		 */
		final Map<String, SyntheticInfo> childIndex = new HashMap<>(32);

		/**
		 * 名字 → SyntheticInfo 的完整索引（两侧各一份）。
		 *
		 * <p>替代 {@code infoByName} 的线性查找：后者要遍历所有 group 的所有成员
		 * （双重循环），而它被**三个 64 轮定稿循环**按"每个方法 × 每个子"调用，
		 * 单轮就是 O(N·children·N)，最坏合计 O(64·N²)。有了索引即为 O(1)。</p>
		 *
		 * <p><b>填充时机</b>：必须在 {@code scan} 建完该侧**全部** SyntheticInfo 之后，
		 * 否则会查到 null（幽灵重注入也会改变方法表）。</p>
		 */
		final Map<String, SyntheticInfo> oldNameIndex = new HashMap<>(64);
		final Map<String, SyntheticInfo> newNameIndex = new HashMap<>(64);

		int step1Pairs;
		int passAPairs;
		int passBPairs;

		/**
		 * 重置上下文状态，为下一次匹配做准备。
		 * <p>由 {@link #align} 在入口与 finally 中各调用一次。</p>
		 */
		void reset() {
			renameMap.clear();
			renameBySimpleName.clear();
			simpleNameWitness.clear();
			ambiguousNames.clear();
			usedOldNames.clear();
			existingNewNames.clear();
			oldNameDescSet.clear();
			fingerprinter.reset();
			currentClass = null;
			oldGroups.clear();
			newGroups.clear();
			childIndex.clear();
			oldNameIndex.clear();
			newNameIndex.clear();
			step1Pairs = 0;
			passAPairs = 0;
			passBPairs = 0;
		}
	}
	//endregion

	//region 主要 API

	/**
	 * 对齐 Lambda 表达式的主入口方法。
	 *
	 * @param oldBytes <b>上一轮对齐后 JVM 里实际生效的字节码</b>（见类级 javadoc 的调用约定）
	 * @param newBytes 本次新编译出的字节码
	 * @return 对齐后的字节码；若没有任何重命名且无孤儿方法需要复活，返回原始 {@code newBytes}；
	 *         若整个流程抛异常，也会记录日志后返回原始 {@code newBytes}（降级不崩溃）
	 */
	public static byte[] align(byte[] oldBytes, byte[] newBytes) {
		if (oldBytes == null || oldBytes.length == 0) return newBytes;

		MatchContext ctx = CONTEXT.get();
		try {
			ctx.reset();

			// 一次读取即拿到 ClassNode：oldCn 会被 resurrectOrphanedLambdas 复用，
			// newCn 会在“无重命名”路径下直接提供 presentKeys。
			ClassNode oldCn = scan(oldBytes, ctx, true);
			ClassNode newCn = scan(newBytes, ctx, false);
			if (!Objects.equals(oldCn.name, newCn.name)) {
				throw new IllegalArgumentException(
					"New class name does not match old class name: " + newCn.name + " != " + oldCn.name);
			}
			ctx.currentClass = oldCn.name;

			LongObjectMap<List<SyntheticInfo>> oldGroups = ctx.oldGroups;
			LongObjectMap<List<SyntheticInfo>> newGroups = ctx.newGroups;

			// 【阶段一】多轮迭代匹配（含 settled 定稿机制）：
			//
			// 解决"祖先链被 hasUnmatchedChild 死锁"问题：
			// 当子 lambda 因描述符变动等原因在旧侧完全没有候选时，它在所有趟里都无法配对（matched == false）。
			// 若只看 matched，父 lambda 的 hasUnmatchedChild 会在所有趟中永远返回 true，导致父及其整条祖先链
			// 全部被挡住，最终在阶段二被迫全部拿 fresh name、旧名全幽灵化（即使祖先方法体与结构完全没变）。
			//
			// settled 机制将"未落定"与"确定无候选"分开：
			// 跑完全部趟（Step 1 -> Pass A -> Pass B）后，对仍未配对的方法，若其子节点已全部落定（已配对或已 settled），
			// 说明该方法已用尽所有候选机会，将其标记为 settled = true。
			// hasUnmatchedChild 忽略已 settled 的子节点，解除对父节点的阻塞，
			// 随后整体重跑各趟，使祖先能够保住旧名。
			while (true) {
				matchAllPasses(ctx);

				boolean newlySettled = false;
				for (int idx : groupOrder(newGroups)) {
					List<SyntheticInfo> g = newGroups.valueAt(idx);
					if (g == null) continue;
					for (SyntheticInfo ni : g) {
						if (!ni.matched && !ni.settled && !ni.ghost && !hasUnmatchedChild(ctx, ni)) {
							ni.settled = true;
							newlySettled = true;
							dbg(() -> "SETTLED " + ni.name + " desc=" + ni.desc);
						}
					}
				}
				if (!newlySettled) break;
			}

			// 【阶段一·校验】配对后的引用一致性校验（收口防线）。
			//
			// 不变量：配对成功的 (新, 旧) 必须"子归属一致" —— 新方法体内的每个子 lambda
			// 最终所属的旧名字，必须与旧方法体引用的那些名字吻合。违反它意味着
			// "父保住名字、子丢了名字"这类静默错位：老 CallSite 拿到了父，
			// 但父内部指向的已经换人。
			//
			// 曾用"shape 是否相等"做这道校验，但那是**恒真**的：shape 在 scan 里定稿后不再变，
			// 而所有配对路径都已强制 sameNestingLevel（shape 相等），因此它永不触发，
			// 只提供虚假的安全感。改为引用归属后是独立信息。
			//
			// 与当初那个 save3 形状 bug 的关系：那个 bug 是**两侧算成同一个错值**，
			// 任何等值校验都放行；它已被两处修复解决（shape 迭代到定稿、哨兵改用 null），
			// 见 scan 末尾对子树形状的定点迭代。本校验防的是另一类失败（子归属错位）。
			verifyRefConsistency(ctx);

			// 【阶段二前】诊断摘要：只在 DEBUG 下，且每个未配对方法**只打一行**。
			//
			// 为什么不直接在 hasUnmatchedChild 里 dbg：它在 Step 1a、跨组、A 趟、B 趟、
			// 外层 do-while 里都会被调用，逐次打印会把真正的信号淹掉。这里在**全部趟收敛后**
			// 汇总一次，于是"中层被叶子挡住 / 叶子本身无候选"是两行直接证据。
			dbgBlockedSummary(ctx);

			// 【阶段二】未匹配的新方法统一处理
			int freshId = 0;
			for (int idx : groupOrder(newGroups)) {
				List<SyntheticInfo> newGroup = newGroups.valueAt(idx);
				if (newGroup == null) continue;

				for (SyntheticInfo ni : newGroup) {
					if (ni.matched || !ni.renameable) continue;

					String  key      = ni.name + ni.desc;
					boolean conflict = ctx.usedOldNames.contains(ni.name)
					                || ctx.oldNameDescSet.contains(key);
					if (conflict) {
						String freshName;
						do {
							freshName = ni.logicalName + (freshId++);
						} while (ctx.usedOldNames.contains(freshName)
						      || ctx.existingNewNames.contains(freshName)
						      || ctx.oldNameDescSet.contains(freshName + ni.desc));

						recordRename(ctx, ni, freshName);
						ctx.usedOldNames.add(freshName);
					} else {
						// 无冲突：保持原名，但同样要留下见证
						recordRename(ctx, ni, ni.name);
						ctx.usedOldNames.add(ni.name);
					}
				}
			}

			// 【阶段二后】双保险：扫描"见证过的简单名 vs 当前未改名方法"的潜在冲突
			detectResidualAmbiguity(ctx);

			// 应用重命名规则
			byte[]    alignedBytes;
			ClassNode alignedCn;
			if (ctx.renameMap.isEmpty()) {
				// 无重命名：直接复用 scan 阶段已经建好的 ClassNode
				alignedBytes = newBytes;
				alignedCn    = newCn;
			} else {
				alignedCn    = applyTransform(newBytes, ctx);
				alignedBytes = writeClass(alignedCn);
			}

			// 从 alignedCn 直接收集 presentKeys，避免 resurrectOrphanedLambdas 再读一遍
			Set<String> presentKeys = collectMethodKeys(alignedCn);

			LAST_STATS = new AlignmentStats(ctx.step1Pairs, ctx.passAPairs, ctx.passBPairs);
			// 传入 oldCn（已解析过一次）—— 避免 resurrectOrphanedLambdas 二次读 oldBytes
			return resurrectOrphanedLambdas(oldCn, alignedBytes, presentKeys, ctx);
		} catch (Exception e) {
			// 降级：不崩溃，返回原始字节码。
			// 默认只记一行（热更失败不该刷屏）；DEBUG 下走 Logger 的 (msg, Throwable)
			// 重载打完整堆栈 —— 而不是直接 printStackTrace，保持输出统一经日志系统。
			if (DEBUG) {
				HotSwapAgent.error("[LambdaAligner] align failed, fallback to newBytes", e);
			} else {
				HotSwapAgent.info("[LambdaAligner] align failed, fallback to newBytes: " + e);
			}
			return newBytes;
		} finally {
			ctx.reset();
		}
	}

	/**
	 * 记录 {@code ni} 需要从 {@code ni.name} 重命名为 {@code newName}。
	 *
	 * <p>无论是否发生改名，都会通过 {@link #witnessSimpleName} 留下见证：
	 * 同一 simpleName 一旦见证了 ≥2 个不同目标（含“保持不变”这种目标），
	 * 就会被标记为歧义，从而禁止 {@code mapValue} 改写字符串常量，
	 * 避免把本应保持原样的 {@code "lambda$foo$0"} 误改成 {@code "lambda$foo$1"}。</p>
	 */
	private static void recordRename(MatchContext ctx, SyntheticInfo ni, String newName) {
		if (!ni.renameable) return;

		// 关键：先见证 simpleName，再考虑是否写入 renameMap
		witnessSimpleName(ctx, ni.name, newName);

		if (!ni.name.equals(newName)) {
			ctx.renameMap.put(ni.name + ni.desc, newName);
		}
	}

	/**
	 * 见证 simpleName 的目标归宿。
	 *
	 * <p>语义：同一 simpleName 在整个类里必须只对应唯一目标，否则无法安全地
	 * 从字符串常量反查新名字。首次见证时若 {@code target} 与 {@code simpleName}
	 * 不同，则登记到 {@link MatchContext#renameBySimpleName}；此后若出现不同
	 * 目标（包括“保持原名”这一目标本身与别的目标冲突），立即撤回登记并记入
	 * {@link MatchContext#ambiguousNames}。</p>
	 */
	private static void witnessSimpleName(MatchContext ctx, String simpleName, String target) {
		if (ctx.ambiguousNames.contains(simpleName)) return;

		String prev = ctx.simpleNameWitness.get(simpleName);
		if (prev == null) {
			ctx.simpleNameWitness.put(simpleName, target);
			if (!simpleName.equals(target)) {
				ctx.renameBySimpleName.put(simpleName, target);
			}
			// simpleName.equals(target) 时无需登记 renameBySimpleName：
			// mapValue 查不到就相当于保持原名，效果一致。
		} else if (!prev.equals(target)) {
			// 出现第二个目标 -> 歧义，撤回此前的 name-only 映射
			ctx.simpleNameWitness.remove(simpleName);
			ctx.renameBySimpleName.remove(simpleName);
			ctx.ambiguousNames.add(simpleName);
		}
	}

	/**
	 * 双保险：扫描“当前未改名的可重命名方法”，若其 simpleName 此前在
	 * {@link MatchContext#renameBySimpleName} 里留下过别名见证，则说明该名字
	 * 在同一类里既有“保持原名”又有“改到别处”的用法 —— 记入歧义并撤回别名映射。
	 *
	 * <p>多数场景下 {@link #witnessSimpleName} 已能在写入时捕获；此处作为
	 * 兜底，防御未来算法调整引入的漏网情况。</p>
	 *
	 * <p><b>覆盖范围与边界</b>：本方法只扫描 {@link MatchContext#newGroups} 中的
	 * 方法——也就是 {@code scan} 阶段被识别为合成方法（{@code ACC_SYNTHETIC}
	 * 或名字命中 {@link MethodFingerprinter#isSyntheticName}）的那些。以下情形
	 * <b>不在覆盖范围内</b>：</p>
	 * <ul>
	 *   <li>名字只作为字符串常量出现在 {@code $deserializeLambda$} 或业务代码中，
	 *       但类里并无对应方法——例如被反射调用、或在别处拼接出来的 lambda 名。</li>
	 *   <li>名字出现在注解值、类常量池等处，但不对应本类的方法定义。</li>
	 * </ul>
	 *
	 * <p>这些边界情况下，{@link MatchContext#renameBySimpleName} 里的别名映射会保留到最后，
	 * 可能造成 {@code mapValue} 把某个字符串常量改成一个实际上并不存在于本类的
	 * 方法名。因为对应字符串无法通过常规方式被 {@code SerializedLambda} 反查
	 * （{@code getImplMethodName} 只会返回真实存在的方法名），触发概率极低；
	 * 但若此类字符串在业务逻辑里被当作与 lambda 有关的名字使用，需自行评估风险。
	 * 真要彻底闭合，需要在 {@code mapValue} 里对每个字符串做“本类中是否存在对应
	 * 名字”的二次校验，代价是额外一遍扫描，与收益不匹配，暂不实现。</p>
	 */
	private static void detectResidualAmbiguity(MatchContext ctx) {
		var newGroups = ctx.newGroups;
		for (int idx = newGroups.nextEntry(-1); idx != -1; idx = newGroups.nextEntry(idx)) {
			List<SyntheticInfo> newGroup = newGroups.valueAt(idx);
			if (newGroup == null) continue;

			for (SyntheticInfo ni : newGroup) {
				if (!ni.renameable) continue;
				if (ctx.renameMap.containsKey(ni.name + ni.desc)) continue; // 已改名
				if (ctx.ambiguousNames.contains(ni.name)) continue;
				if (ctx.renameBySimpleName.remove(ni.name) != null) {
					ctx.simpleNameWitness.remove(ni.name);
					ctx.ambiguousNames.add(ni.name);
				}
			}
		}
	}

	/**
	 * 收集方法体里<b>直接引用</b>的本类 lambda 系方法名（本方法的"子"lambda）。
	 *
	 * <p>数据来源两处，都指向方法本身而非名字：invokedynamic 的 implMethod 句柄
	 * （内层 lambda 的定义），以及直接的方法调用（lambda 体里显式调用某个合成方法）。
	 * 只收集本类的、命中 lambda 系命名模式的那些。</p>
	 */
	private static List<String> collectChildLambdaNames(MethodNode mn, String owner) {
		List<String> children = null;
		for (AbstractInsnNode n : mn.instructions) {
			String callee = null;
			if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
			    && i.bsmArgs[1] instanceof Handle h && owner.equals(h.getOwner())) {
				callee = h.getName();
			} else if (n instanceof MethodInsnNode m && owner.equals(m.owner)) {
				callee = m.name;
			}
			if (callee == null || !MethodFingerprinter.isSyntheticName(callee)) continue;
			if (children == null) children = new ArrayList<>(4);
			if (!children.contains(callee)) children.add(callee);   // 去重但保持出现顺序
		}
		return children == null ? Collections.emptyList() : children;
	}

	/**
	 * 当双方都有子 lambda 时，检查"子的配对对象"是否与旧方法的子逐个吻合。
	 *
	 * <p>为什么需要：{@link #sameSemantics} 是递归语义指纹，后代一旦被编辑，祖先的语义
	 * 指纹也会变，于是祖先拿不到任何候选（实测 save3 的 V2→V3：中层/外层因此被幽灵化，
	 * 子被改体是 update 期最常见的动作）。子树等价这条证据在"后代被改"时必然失效，
	 * 此时退而求其次用**结构证据**：子已经落定（{@link #hasUnmatchedChild} 保证），
	 * 顺着"新子被配给了哪个旧方法"看，只有"子正好是那些旧方法"的旧候选才有资格当选。
	 * 逐层传递，不需要知道嵌套深度。</p>
	 *
	 * <p>只在双方都有子、且子数量一致时启用（结构不符已由 {@link #sameNestingLevel}
	 * 否决）；子尚未落定或信息不足时返回 true，不额外限制。</p>
	 */
	private static boolean calleesPairTo(SyntheticInfo ni, SyntheticInfo oi) {
		if (ni.children.isEmpty() || oi.children.isEmpty()) return true;
		if (ni.children.size() != oi.children.size()) return false;
		for (int i = 0; i < ni.children.size(); i++) {
			SyntheticInfo ci = ni.childInfos.get(i);
			String want = oi.children.get(i);
			if (ci == null) return true;                  // 信息不足，不额外限制
			if (ci.matchedWith != null) {
				if (!ci.matchedWith.name.equals(want)) return false;
			} else if (!ci.name.equals(want)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 该新方法是否还有"尚未落定"的子 lambda（子仍是未匹配状态）。
	 *
	 * <p><b>为什么父必须等子</b>：lambda 可以嵌套，而指纹里内层 lambda 的名字被
	 * {@code #SYNTHETIC_METHOD#} 屏蔽，于是"父"与"子"在指纹上可能完全等价 ——
	 * 例如 {@code run(() -> Time.run(10, () -> doA()))}，父的体就是"求值一个
	 * {@code Time.run(10, 子)}"，与子自身同构。此时若让子先被别处抢走名字，
	 * 父的方法体里那句指向子的引用会被重映射到别人的名字上：父保住了名字、子却丢了，
	 * 语义静默对调。</p>
	 *
	 * <p>实测复现与决策轨迹见 {@code scratch/hstest/swap2/}。判断只看"子是否已 matched"，
	 * 因为匹配是单调的：一旦子落定就不会再变，父可以安全地基于它做决定。</p>
	 */
	private static boolean hasUnmatchedChild(MatchContext ctx, SyntheticInfo ni) {
		if (ni.children.isEmpty()) return false;
		for (String child : ni.children) {
			SyntheticInfo ci = ctx.childIndex.get(child);
			if (ci != null && !ci.matched && !ci.settled) {
				// 这是"祖先链被死锁"那类问题的现场证据：父被未落定的子挡住。
				// 若子已确认无候选被标为 settled，则不再阻挡父，允许父保住名字。
				dbg(() -> "SKIP(parent has unmatched child) parent=" + ni.name
					+ " child=" + child + " (childMatched=false, settled=false)");
				return true;
			}
		}
		return false;
	}

	/**
	 * 配对后的<b>引用一致性</b>校验（对已有不变量的收口，非启发式）。
	 *
	 * <p>不变量：若新方法 {@code ni} 与旧方法 {@code oi} 配成一对，则 {@code ni} 体内的
	 * 每个子 lambda，其<b>最终归属</b>必须与 {@code oi} 体内引用的那些旧名字一致 ——
	 * 否则就是"父保住了名字、子丢了名字"那类静默错位：老 CallSite 拿到了父，
	 * 但父内部指向的已经换人。</p>
	 *
	 * <p><b>为什么不用 shape 校验</b>：`shape` 在 {@code scan} 里算完就不再变，而所有配对路径
	 * 都已强制 {@link #sameNestingLevel}（即 shape 相等），因此"检查 shape 是否相等"恒真、
	 * 永不触发，只会给人虚假的安全感。本校验改用<b>引用归属</b>，是独立信息。</p>
	 *
	 * <p>违反时撤销该配对：旧方法走幽灵、新方法走阶段二拿避障名，把静默错位降级为显式熔断。</p>
	 *
	 * @return 撤销的配对数
	 */
	private static int verifyRefConsistency(MatchContext ctx) {
		int undone = 0;
		for (int idx : groupOrder(ctx.newGroups)) {
			List<SyntheticInfo> newGroup = ctx.newGroups.valueAt(idx);
			if (newGroup == null) continue;
			for (SyntheticInfo ni : newGroup) {
				SyntheticInfo oi = ni.matchedWith;
				if (oi == null) continue;
				if (sameChildOwnership(ctx, ni, oi)) continue;

				// 撤销：清掉改名登记与双方的匹配状态。
				// 注意**不回滚** simpleNameWitness / renameBySimpleName：那两张表记录的是
				// "同一 simpleName 的唯一目标"，撤销配对并不会抹掉"曾经见证过"这一事实。
				// 后果偏保守（该名字仍可能被判已使用/歧义），不会产生错误映射。
				// 不尝试回滚的原因：witnessSimpleName 未记录"本次调用是否插入/改写了该条目"，
				// 盲目 remove 会把别的配对写入的见证一并删掉，反而制造不一致。
				if (ctx.renameMap.get(ni.name + ni.desc) != null) {
					ctx.renameMap.remove(ni.name + ni.desc);
				}
				ni.matchedWith = null;
				ni.matched = false;
				oi.matched = false;
				ctx.usedOldNames.remove(oi.name);
				undone++;
				HotSwapAgent.warn("[LambdaAligner] 引用一致性被违反，撤销配对：" + ni.name
					+ " 的子 " + ni.children + " 与 " + oi.name + " 的子 " + oi.children
					+ " 不一致（静默错位已降级为熔断）");
			}
		}
		return undone;
	}

	/**
	 * DEBUG 诊断摘要：在全部趟收敛后、阶段二之前，对每个**仍未配对的可改名新方法**打印一行，
	 * 区分两种完全不同的处境。
	 *
	 * <ul>
	 *   <li>{@code BLOCKED parent=X by child=Y} —— 子未落定，父被 {@link #hasUnmatchedChild}
	 *       挡住。若该子最终无候选，父会在所有趟里一直被挡住，与子一起走幽灵。</li>
	 *   <li>{@code NO-CANDIDATE X} —— 没有被挡，是自己找不到合适旧方法。</li>
	 * </ul>
	 *
	 * <p><b>候选数是近似计数</b>：只按 {@link #isSignatureCompatible} + {@link #sameNestingLevel}
	 * 计，<b>不调用 {@code acceptCandidate}</b> —— 后者要求 {@code sameSemantics}，而后代被编辑时
	 * 祖先的语义指纹必然变化，用它计数会把"结构上可用"的候选误算成 0，反而掩盖真相。</p>
	 */
	private static void dbgBlockedSummary(MatchContext ctx) {
		if (!DEBUG) return;
		for (int idx : groupOrder(ctx.newGroups)) {
			List<SyntheticInfo> g = ctx.newGroups.valueAt(idx);
			if (g == null) continue;
			for (SyntheticInfo ni : g) {
				if (ni.matched || !ni.renameable || ni.ghost) continue;

				String blockedBy = null;
				for (String c : ni.children) {
					SyntheticInfo ci = ctx.childIndex.get(c);
					if (ci != null && !ci.matched && !ci.settled) { blockedBy = c; break; }
				}
				long cand = countCompatCandidates(ctx, ni);
				long leafCand = blockedBy != null ? countCompatCandidates(ctx,
					ctx.childIndex.get(blockedBy)) : -1;

				if (blockedBy != null) {
					String b = blockedBy;
					long lc = leafCand;
					dbg(() -> "BLOCKED parent=" + ni.name + " by child=" + b
						+ "(childMatched=false, 旧侧候选数=" + lc + ")"
						+ " parentHash=" + ni.hash + " parentSem=" + ni.semanticHash
						+ " parentCand=" + cand);
				} else {
					dbg(() -> "NO-CANDIDATE " + ni.name
						+ " hash=" + ni.hash + " sem=" + ni.semanticHash
						+ " 旧侧候选数=" + cand);
				}
			}
		}
	}

	/** 旧侧与 {@code ni} 结构上兼容（签名 + 层级）的未匹配候选数，仅供诊断。 */
	private static long countCompatCandidates(MatchContext ctx, SyntheticInfo ni) {
		if (ni == null) return -1;
		long n = 0;
		for (int idx : groupOrder(ctx.oldGroups)) {
			List<SyntheticInfo> g = ctx.oldGroups.valueAt(idx);
			if (g == null) continue;
			for (SyntheticInfo oi : g) {
				if (oi.matched || oi.ghost || !oi.renameable) continue;
				if (!isSignatureCompatible(ctx.currentClass, oi, ni)) continue;
				if (!sameNestingLevel(ni, oi)) continue;
				n++;
			}
		}
		return n;
	}

	/**
	 * {@code ni} 体内**已配对**的子 lambda，其归属是否与 {@code oi} 引用的旧名字一致。
	 *
	 * <p>只比较**已配对**的子：未配对的子还没有最终名字，用它的编译名去比旧名是无效推断
	 * （同一次保存里前面插入一个 lambda 会让编译名整体位移，一个仅仅签名变了、尚未配对的
	 * 子，编译名就不等于旧名），据此撤销会把**合法的父配对**误撤成幽灵。</p>
	 *
	 * <p>同时这也把两类结果区分开：
	 * "子丢了名字"（走幽灵/熔断）是**设计内的降级**，不判违规；
	 * "子被换成了别人"（已配对的子归到了 {@code oi.children} 之外的名字）才是**静默错位**。
	 * 只有后者需要撤销。</p>
	 *
	 * <p>不比较未配对子还顺带消除了对遍历顺序的依赖：撤销某个配对会改变其祖先看到的
	 * 归属关系，若把未配对子也算进来，结果就取决于先处理谁 —— 这与
	 * {@code TEST_REVERSE_GROUP_ORDER} 想守住的性质相悖。</p>
	 */
	private static boolean sameChildOwnership(MatchContext ctx, SyntheticInfo ni, SyntheticInfo oi) {
		for (String c : ni.children) {
			SyntheticInfo ci = infoByName(ctx, false, c);
			if (ci == null) return true;                       // 信息不足：不判违规
			if (ci.matchedWith == null) continue;              // 未配对：无从判断，跳过
			// 已配对的子必须归到对方引用过的旧名字上
			if (!oi.children.contains(ci.matchedWith.name)) return false;
		}
		return true;
	}

	/**
	 * 判断新方法 {@code ni} 与旧方法 {@code oi} 是否处在同一层级（都是叶子，或两者的
	 * 子 lambda 指纹集合一致）。
	 *
	 * <p><b>为什么需要它</b>：嵌套 lambda 下"父"与"子"可能指纹完全相同 —— 父的体就是
	 * "求值一个 {@code Time.run(10, 子)}"，与子自身同构，而子的名字又被
	 * {@code #SYNTHETIC_METHOD#} 屏蔽。此时若只比指纹，父会配到子（或反之），
	 * 造成"父保住名字、子丢了名字"的语义静默对调。</p>
	 *
	 * <p>区分办法不靠推断嵌套深度，而是比<b>子树形状</b>（{@link SyntheticInfo#shape}）：
	 * 形状只由 indy 引用拓扑决定，父有子、子是叶子，两者必然不同。
	 * 指纹相同但层级不同的候选会被否决。</p>
	 *
	 * <p>实测场景见 {@code scratch/hstest/swap2/}（删除变体）：新旧外层同 hash、
	 * 新旧内层也同 hash，仅凭 hash 无法区分谁是谁。</p>
	 */
	private static boolean sameNestingLevel(SyntheticInfo ni, SyntheticInfo oi) {
		// 比**子树形状**：只由 indy 引用拓扑决定，与方法体内容无关。
		//
		// 演进过程（两个方向都踩过）：
		//   • 早先比 childHashes 的**值** ⇒ 后代被编辑时祖先形状被误判（实测 save3 的
		//     单轮 V2→V3，整条祖先链被幽灵化）；
		//   • 改成只比"有无子 + 子数量"⇒ 外层与中层结构等价（都是 1 个子），互换无人拦
		//     （实测 save3 两轮序列：$3 与 $4 互换，老回调静默改了语义）。
		// 形状同时解决两者：与内容无关、但能区分外层/中层/叶子。
		return ni.shape.equals(oi.shape);
	}

	/**
	 * 执行阶段一的全部匹配趟（Step 1 证据匹配 -> Step 2 A 趟形状深度匹配 -> Step 2 B 趟顺序兜底）。
	 *
	 * @return 本轮是否至少产生了一次新配对
	 */
	private static boolean matchAllPasses(MatchContext ctx) {
		LongObjectMap<List<SyntheticInfo>> oldGroups = ctx.oldGroups;
		LongObjectMap<List<SyntheticInfo>> newGroups = ctx.newGroups;
		boolean anyProgressed = false;

		// 1. Step 1（同组同 hash）+ 跨组指纹（证据匹配，迭代至收敛）
		boolean progressed;
		do {
			progressed = false;
			for (int idx : groupOrder(newGroups)) {
				List<SyntheticInfo> newGroup = newGroups.valueAt(idx);
				List<SyntheticInfo> oldGroup = oldGroups.get(newGroups.keyAt(idx));
				if (newGroup == null || oldGroup == null) continue;
				progressed |= step1a(ctx, newGroup, oldGroup);
			}
			progressed |= matchByFingerprintAcrossGroups(ctx);
			anyProgressed |= progressed;
		} while (progressed);

		// 2. A 趟：全类范围配对"上行深度 + shape 都相等"的对（迭代至收敛）
		boolean aProgressed;
		do {
			aProgressed = false;
			for (int idx : groupOrder(newGroups)) {
				List<SyntheticInfo> newGroup = newGroups.valueAt(idx);
				List<SyntheticInfo> oldGroup = oldGroups.get(newGroups.keyAt(idx));
				if (newGroup == null || oldGroup == null) continue;
				aProgressed |= step2PassA(ctx, newGroup, oldGroup);
			}
			anyProgressed |= aProgressed;
		} while (aProgressed);

		// 3. B 趟：顺序回退兜底（迭代至收敛）
		boolean step2Progressed;
		do {
			step2Progressed = false;
			for (int idx : groupOrder(newGroups)) {
				List<SyntheticInfo> newGroup = newGroups.valueAt(idx);
				List<SyntheticInfo> oldGroup = oldGroups.get(newGroups.keyAt(idx));
				if (newGroup == null || oldGroup == null) continue;
				step2Progressed |= step2(ctx, newGroup, oldGroup);
			}
			anyProgressed |= step2Progressed;
		} while (step2Progressed);

		return anyProgressed;
	}

	/**
	 * Step 1a：同组、同 hash、同名 —— 证据最强的一档。
	 *
	 * @return 本趟是否至少配对了一个方法（供层级迭代判断是否需要再跑一轮）
	 */
	private static boolean step1a(MatchContext ctx, List<SyntheticInfo> newGroup, List<SyntheticInfo> oldGroup) {
		boolean progressed = false;

		// 第一优先：同组、同 hash、**同名**（证据最强）
		for (SyntheticInfo ni : newGroup) {
			if (!ni.renameable || ni.matched) continue;
			if (hasUnmatchedChild(ctx, ni)) continue;
			for (SyntheticInfo oi : oldGroup) {
				if (!acceptCandidate(ctx, ni, oi)) continue;
				if (!oi.name.equals(ni.name)) continue;
				pair(ctx, ni, oi, "STEP1");
				ctx.step1Pairs++;
				progressed = true;
				break;
			}
		}

		// 第二优先：同组、同 hash，**不限名字** —— 负责"方法体一字未改、但编译期序号变了"。
		// 之所以能安全地不限名字，是因为加了 hasUnmatchedChild 与 sameNestingLevel 两道护栏：
		// 父与子可能指纹相同（父的体就是"求值一个子"），缺任何一道都会造成
		// "父保住名字、子丢了名字"的静默对调。实测复现见 scratch/hstest/swap2/。
		for (SyntheticInfo ni : newGroup) {
			if (!ni.renameable || ni.matched) continue;
			if (hasUnmatchedChild(ctx, ni)) continue;
			for (SyntheticInfo oi : oldGroup) {
				if (!acceptCandidate(ctx, ni, oi)) continue;
				pair(ctx, ni, oi, "STEP1");
				ctx.step1Pairs++;
				progressed = true;
				break;
			}
		}

		return progressed;
	}

	/** 候选是否可接受：未匹配、非幽灵、指纹与签名一致、且处于同一嵌套层级。 */
	private static boolean acceptCandidate(MatchContext ctx, SyntheticInfo ni, SyntheticInfo oi) {
		if (oi.matched || oi.ghost) return false;
		// 旧候选也必须可改名 —— 与 ni 上的限制对称。
		// scan 把 access$ / Kotlin $annotations 这类设为非 renameable，用意是不让它们参与改名；
		// 若这里不查，一个空体新 lambda 仍可能认领空体旧 $annotations 的名字。
		if (!oi.renameable) return false;
		// 用递归语义指纹而非票据指纹：票据指纹屏蔽了子 lambda 的名字，
		// 使"整棵树只差最深处叶子"的方法互相撞车（三层嵌套即如此）。
		// 语义指纹一致 ⇔ 整棵子树等价，因此不会把两个不同的 lambda 配成一对。
		if (!sameSemantics(ni, oi)) return false;
		if (!isSignatureCompatible(ctx.currentClass, oi, ni)) return false;
		if (!sameNestingLevel(ni, oi)) return false;   // 否决：父不得配子
		return calleesPairTo(ni, oi);                  // 正向：子必须配到对方的子
	}

	/** 两个方法的递归语义指纹是否一致（未定稿时退回票据指纹）。 */
	private static boolean sameSemantics(SyntheticInfo a, SyntheticInfo b) {
		long sa = a.semanticHash != 0 ? a.semanticHash : a.hash;
		long sb = b.semanticHash != 0 ? b.semanticHash : b.hash;
		return sa == sb;
	}

	/** 配对并登记：把 {@code ni} 改名为 {@code oi} 的名字。 */
	private static void pair(MatchContext ctx, SyntheticInfo ni, SyntheticInfo oi, String pass) {
		dbg(() -> "[" + pass + "] PAIR " + ni.name + "(shape=" + ni.shape + " depth=" + ni.upDepth + ")"
			+ " -> " + oi.name + "(shape=" + oi.shape + " depth=" + oi.upDepth + ")");
		recordRename(ctx, ni, oi.name);
		ni.matchedWith = oi;
		ni.matched = true;
		oi.matched = true;
		ctx.usedOldNames.add(oi.name);
	}

	/**
	 * Step 2 的 **A 趟**：只配"上行深度 + shape 都相等"的对。
	 *
	 * <p>必须在**全类**上先跑完 A 趟，再进 B 趟（见调用处的说明）—— 混在同一个单组循环里
	 * 不够：没有同深度候选的新方法会立刻落到 B 趟，凭先到先得抢走本该属于别人的旧名字。</p>
	 *
	 * <p>只收窄、不做硬否决：B 趟仍会兜底，因此"把叶子用新 lambda 包一层"这类合法编辑
	 * （深度变了）不会因此丢名字、被熔断。</p>
	 *
	 * <p>深度与 shape 都由两侧各自的 indy 关系算出、与匹配状态无关，所以这里对叶子可直接
	 * 判定，不与 {@link #hasUnmatchedChild} 互等。</p>
	 *
	 * @return 本趟是否至少配成一对
	 */
	private static boolean step2PassA(MatchContext ctx, List<SyntheticInfo> newGroup,
	                                  List<SyntheticInfo> oldGroup) {
		boolean progressed = false;
		for (SyntheticInfo ni : newGroup) {
			if (ni.matched || !ni.renameable) continue;
			if (ni.upDepth < 0) continue;                 // 未定稿：不参与
			if (hasUnmatchedChild(ctx, ni)) continue;

			SyntheticInfo bestOld = null;
			for (SyntheticInfo oi : oldGroup) {
				if (oi.matched || oi.ghost) continue;
				if (!oi.renameable) continue;             // 与 ni 上的限制对称
				if (ctx.usedOldNames.contains(oi.name)) continue;
				if (oi.upDepth != ni.upDepth) continue;   // 深度必须相等
				if (!sameNestingLevel(ni, oi)) continue;  // 形状必须相等
				if (!isSignatureCompatible(ctx.currentClass, oi, ni)) continue;
				if (oi.name.equals(ni.name)) { bestOld = oi; break; }   // 同名者优先
				if (bestOld == null) bestOld = oi;
			}
			if (bestOld == null) continue;

			dbg(() -> "A-PASS ni=" + ni.name + " depth=" + ni.upDepth + " shape=" + ni.shape
				+ " oldCandidates=" + oldGroup.stream()
					.map(o -> o.name + ":d" + o.upDepth + ":" + o.shape
						+ (o.matched ? ":M" : "") + (o.ghost ? ":G" : "")).toList());
			pair(ctx, ni, bestOld, "PASS_A");
			ctx.passAPairs++;
			progressed = true;
		}
		return progressed;
	}

	/**
	 * Step 2：顺序回退 —— 只处理"指纹也对不上、仍无归宿"的新方法（B 趟）。
	 *
	 * <p>A 趟（{@link #step2PassA}）已先在**全类**上跑完并配掉"深度 + shape 都相等"的对，
	 * 这里再用放宽的候选条件兜底。两趟分开的原因见 {@link #step2PassA}。</p>
	 *
	 * @return 本趟是否至少配对了一个方法
	 */
	private static boolean step2(MatchContext ctx, List<SyntheticInfo> newGroup, List<SyntheticInfo> oldGroup) {
		boolean progressed = false;
		for (SyntheticInfo ni : newGroup) {
			if (ni.matched || !ni.renameable) continue;
			if (hasUnmatchedChild(ctx, ni)) continue;

			SyntheticInfo bestOld = null;

			// 第一优先级：组内同名且签名逻辑等价
			//
			// 两道约束缺一不可：
			//   • oi.matched    —— 旧方法只能被用一次；
			//   • usedOldNames  —— 旧**名字**只能被占一次。
			// 第二道是必需的：不同的新方法可以各自选中同一个未被 matched 的旧方法
			// （同一个 oldGroup 里按位置找，很容易撞到同一个候选），于是同一个旧名字
			// 会被登记给两个不同描述符的新方法，renameMap 里一个键覆盖另一个，
			// 结果新方法内部对被改名的子的引用指向了别处。实测见 scratch/hstest/deep2/ 变体乙：
			// 新的叶子占了旧 $2，而新外层内部引用的 $2 实际指向新的中层。
			if (bestOld == null)
			for (SyntheticInfo oi : oldGroup) {
				if (oi.matched || oi.ghost) continue;
				if (!oi.renameable) continue;   // 与 ni 上的限制对称
				if (ctx.usedOldNames.contains(oi.name)) continue;
				if (!isSignatureCompatible(ctx.currentClass, oi, ni)) continue;
				if (!sameNestingLevel(ni, oi)) continue;
				if (oi.name.equals(ni.name)) { bestOld = oi; break; }
			}

			// 第二优先级：第一个签名逻辑等价的未匹配旧方法
			if (bestOld == null) {
				for (SyntheticInfo oi : oldGroup) {
					if (oi.matched || oi.ghost) continue;
					if (!oi.renameable) continue;   // 与 ni 上的限制对称
					if (ctx.usedOldNames.contains(oi.name)) continue;
					if (!isSignatureCompatible(ctx.currentClass, oi, ni)) continue;
					if (!sameNestingLevel(ni, oi)) continue;
					bestOld = oi;
					break;
				}
			}

			if (bestOld == null) continue;

			// （a）底线守护：真正的危险信号是"名字换了主人"，而不是"方法体变了"。
			//
			// 为什么用名字而不是指纹：
			//   • 名字没变（ni.name == bestOld.name）⇒ 老 CallSite 依然指向它原本
			//     要调的那个方法，无论内部怎么改都是原地更新，不构成故障。
			//     典型：容器 lambda（体内挂着内层 lambda）——改一个内层 lambda
			//     会连带改变外层的槽位布局，使外层指纹必然变化，但外层名字保住了，
			//     老 CallSite 执行的就是"新容器 + 已重映射的内层"，行为正确。
			//     若按指纹报警，这种正常编辑会天天刷屏，把真信号淹掉。
			//   • 名字变了 ⇒ 组内同形候选之间发生了错位让位，老 CallSite 被绑到了
			//     另一个方法体上——这才是需要人去看的情况。
			//
			// 判据不含"是不是容器"的推断：容器特征（体内含指向本类合成方法的
			// invokedynamic）虽然能识别，但"名字是否保住"本身就是充分且更严的证据，
			// 无需额外遍历指令。
			if (!ni.name.equals(bestOld.name)) {
				warnPositionalMismatch(ctx.currentClass, bestOld, ni.name, ni.desc);
			}
			recordRename(ctx, ni, bestOld.name);
			// 与 pair() 保持一致：登记配对对象。否则 align 末尾的引用一致性校验
			// 看不到 Step 2 的配对（它按 matchedWith 遍历），校验会漏掉这一批。
			ni.matchedWith = bestOld;
			ni.matched = true;
			bestOld.matched = true;
			ctx.usedOldNames.add(bestOld.name);
			ctx.passBPairs++;
			progressed = true;
		}
		return progressed;
	}

	/**
	 * 跨逻辑组的指纹匹配：把"方法体一字未改、但逻辑组变了"的 lambda 认领回旧名。
	 *
	 * <p>这是对 {@link #align} 中 Step 2 顺序回退的根本性补强。逻辑组的分组键是
	 * {@code compositeHash(logicalName, normalizedDesc)}，而 {@code logicalName} 来自
	 * <b>承载 lambda 的那个方法名</b>。因此下面两种完全无害的操作会打散分组：</p>
	 * <ul>
	 *   <li>在承载方法开头插入一个新的 lambda —— 后续 lambda 的编译期序号整体推移，
	 *       组内出现"多个同形候选"，Step 2 按顺序配对 → <b>方法体对调</b>；</li>
	 *   <li>把 lambda 挪到另一个方法（或给承载方法改名）—— 逻辑组直接变了，
	 *       即使方法体一个字节都没动也匹配不上。</li>
	 * </ul>
	 *
	 * <p>本方法在进入顺序回退之前，先做一次全类扫描：新方法若与某个未匹配的旧方法
	 * <b>指纹完全相同</b>，直接认领其旧名。指纹只由方法体指令序列决定（行号、局部
	 * 变量表都被 {@link MethodFingerprinter} 忽略），插入新 lambda 对它没有任何影响，
	 * 顺序依赖由此被切断。</p>
	 *
	 * <p><b>为什么安全</b>：指纹相同意味着方法体逐条指令等价，认领旧名只是让老
	 * {@code CallSite} 与它原本要调用的方法体重新对上，不改变任何行为。指纹不同则
	 * 完全不参与匹配（缺口仍由顺序回退处理，并触发 {@link #warnPositionalMismatch}）。
	 * 因为只认领"指纹吻合"的旧方法，也不存在"劫持别人名字"的风险。</p>
	 *
	 * <p><b>选择顺序</b>：先在<b>同组</b>里找指纹相同的未匹配旧方法（最大限度保留
	 * 原有配对倾向），再退到全类范围。全类范围内遇到多个同指纹候选时，优先同名，
	 * 否则取分组遍历顺序里的第一个。</p>
	 *
	 * <p><b>能力边界（实测结论，避免过度承诺）</b>：本方法只能区分“方法体不同”的 lambda。
	 * 指纹由方法体的指令序列决定，因此：</p>
	 * <ul>
	 *   <li>{@code t.button("新建", () -> create());} 与 {@code () -> save();} —— 被调方法名
	 *       参与指纹（{@link MethodFingerprinter#visitMethodInsn} 会把 name 计入），
	 *       三个按钮的指纹互不相同，插入后可被正确认领；</li>
	 *   <li>三个 lambda 体<b>逐字节相同</b>（例如都只写 {@code () -> create()}）时，
	 *       指纹只有一个值，本方法无法区分谁是谁 —— 但此时“谁是谁”在类文件里本来就
	 *       不存在答案，且方法体相同意味着对调也不改变行为，因此不构成故障；</li>
	 *   <li>{@code this::create} 这种<b>方法引用</b>根本不产生 synthetic lambda 方法
	 *       （字节码里是直接指向 {@code create} 的方法句柄），因此不在本对齐器的处理范围内，
	 *       也就谈不上“认领名字”。它只会作为普通方法增删出现在 DIFF 里。</li>
	 * </ul>
	 *
	 * <p>因此真正的风险区间是“多个 lambda 体相似但不相同、且承载同一个方法”，
	 * 这时才会出现对调；该情形由 {@link #warnPositionalMismatch} 留痕。</p>
	 */
	private static boolean matchByFingerprintAcrossGroups(MatchContext ctx) {
		boolean progressed = false;
		var newGroups = ctx.newGroups;
		var oldGroups = ctx.oldGroups;

		for (int idx : groupOrder(newGroups)) {
			List<SyntheticInfo> newGroup = newGroups.valueAt(idx);
			if (newGroup == null) continue;

			for (SyntheticInfo ni : newGroup) {
				if (ni.matched || !ni.renameable) continue;
				if (hasUnmatchedChild(ctx, ni)) continue;

				SyntheticInfo bestOld = null;

				// 第一优先级：同组内指纹相同（保持 Step 1 之外的原配对倾向）
				List<SyntheticInfo> sameGroup = oldGroups.get(newGroups.keyAt(idx));
				if (sameGroup != null) {
					bestOld = firstFingerprintMatch(ctx, sameGroup, ni, true);
				}
				// 第二优先级：全类范围内指纹相同
				if (bestOld == null) {
					outer:
					for (int k = oldGroups.nextEntry(-1); k != -1; k = oldGroups.nextEntry(k)) {
						List<SyntheticInfo> g = oldGroups.valueAt(k);
						if (g == null || g == sameGroup) continue;
						SyntheticInfo oi = firstFingerprintMatch(ctx, g, ni, true);
						if (oi != null) { bestOld = oi; break outer; }
					}
				}
				if (bestOld == null) continue;

				pair(ctx, ni, bestOld, "STEP1_CROSS");
				ctx.step1Pairs++;
				progressed = true;
			}
		}
		return progressed;
	}

	/**
	 * 在 {@code group} 中找第一个与 {@code ni} 指纹相同且未被匹配的旧方法。
	 *
	 * @param preferSameName 是否优先命中与 {@code ni} 同名的候选（同名意味着
	 *                       "编译期序号都没变"，是更强的证据）
	 * @return 命中的旧方法信息；没有则返回 {@code null}
	 */
	private static SyntheticInfo firstFingerprintMatch(MatchContext ctx, List<SyntheticInfo> group,
	                                                   SyntheticInfo ni, boolean preferSameName) {
		if (group == null) return null;
		String owner = ctx.currentClass;

		if (preferSameName) {
			for (SyntheticInfo oi : group) {
				if (oi.matched || oi.ghost) continue;
				if (!oi.renameable) continue;                       // 与 ni 上的限制对称
				if (ctx.usedOldNames.contains(oi.name)) continue;   // 旧名只能被占一次
				if (!sameSemantics(ni, oi)) continue;
				if (!isSignatureCompatible(owner, oi, ni)) continue;
				if (!sameNestingLevel(ni, oi)) continue;
				if (oi.name.equals(ni.name)) return oi;
			}
		}
		for (SyntheticInfo oi : group) {
			if (oi.matched || oi.ghost) continue;
			if (!oi.renameable) continue;                       // 与 ni 上的限制对称
			if (ctx.usedOldNames.contains(oi.name)) continue;   // 旧名只能被占一次
			if (!sameSemantics(ni, oi)) continue;
			if (!isSignatureCompatible(owner, oi, ni)) continue;
			if (!sameNestingLevel(ni, oi)) continue;
			return oi;
		}
		return null;
	}

	/** 顺序回退告警去重缓存：同一处错配只报一次。 */
	private static final Set<String> WARNED_REALIGNMENTS =
		Collections.newSetFromMap(new ConcurrentHashMap<>());

	/**
	 * 清空"只报一次"的去重缓存（{@link #WARNED_REALIGNMENTS} 与
	 * {@link #LOGGED_ORPHANS}）。
	 *
	 * <p><b>当前未被调用</b>（按项目决定保留为可选 API）。因此两个去重集合
	 * <b>在本进程内只增不减</b>，带来两个已知后果：</p>
	 * <ul>
	 *   <li>某个 lambda 在 V2 被删除、V3 又被加回时，其孤儿日志不会再次打印；</li>
	 *   <li>集合大小随"历史上出现过的不同 location 数"单调增长（量级通常很小）。</li>
	 * </ul>
	 *
	 * <p>若要接入热更流程，应在每轮 transform 开始时调用一次，使"每轮都能重新报告"
	 * 且避免无界增长。</p>
	 */
	public static void clearLoggedWarnings() {
		WARNED_REALIGNMENTS.clear();
		clearLoggedOrphans();
	}

	/**
	 * 底线守护：顺序回退把"旧名字交给了另一个新方法"时留痕。
	 *
	 * <p>这条告警对应最难排查的一类故障：<b>行为悄悄改变而不抛异常</b>。当同一逻辑组里
	 * 出现多个"归一化描述符完全相同"的 lambda（典型成因：把若干 lambda 都写在一个方法里，
	 * 且它们的捕获列表形状一致）时，{@link #align} 的顺序回退只能按组内出现顺序配对。
	 * 此时若在承载方法开头插入一个新的 lambda，后续 lambda 的整体位移会让两个回调
	 * <b>互相对调</b>，老 {@code CallSite} 去执行另一个回调的方法体。</p>
	 *
	 * <p><b>触发条件刻意收窄为"名字换了主人"</b>（{@code ni.name != bestOld.name}）。
	 * 仅仅"方法体变了"不足以告警：绝大多数编辑（含容器 lambda 内的内层改动）都会让指纹变化，
	 * 但只要名字保住，老 {@code CallSite} 依然是原地更新、行为正确。按指纹报警会让正常
	 * 编辑天天刷屏，真正的信号反而被淹掉。名字换手才是"老 CallSite 被绑到别的方法体上"
	 * 的确证。</p>
	 *
	 * <p>不阻断配对的原因："只改了方法体"是最常见的开发动作，而它本来就该保住名字；
	 * 因此这里只报告。<b>根治办法</b>是把身份交给结构而不是位置：让每个 lambda 有
	 * 独立的承载方法（例如各自一个私有方法），逻辑组自然隔离，顺序回退不再参与。</p>
	 *
	 * <p>去重键为 {@code 类名 + 新方法名 + 旧方法名}，同一处错配只打印一次。</p>
	 */
	private static void warnPositionalMismatch(String owner, SyntheticInfo oi, String newName, String newDesc) {
		String key = owner + "#" + newName + "<-" + oi.name;
		if (!WARNED_REALIGNMENTS.add(key)) return;

		HotSwapAgent.warn("[LambdaAligner] 顺序回退配对但方法体不一致 " + owner
			+ "：旧 " + oi.name + oi.desc + " <- 新 " + newName + newDesc
			+ "。同一逻辑组内存在多个同形 lambda 时，插入/删除会使其序号整体位移，"
			+ "此时旧名字会被交给一个语义不同的方法：老 CallSite **不会抛异常**，"
			+ "而是安静地执行新方法体（实测确认：deep2 变体乙里旧 $2 承载了 doB2 的语义）。"
			+ "建议把每个 lambda 放进独立的私有方法，让身份由结构而非位置决定。");
	}
	//endregion

	//region 字节码转换 + 扫描

	/**
	 * 应用转换规则到字节码，返回 remapped 后的 {@link ClassNode}。
	 *
	 * <p>为什么返回 {@code ClassNode} 而不是 {@code byte[]}：让调用方在写出之前
	 * 有机会直接从同一份节点上收集 {@code name + desc} 集合（供
	 * {@link #resurrectOrphanedLambdas} 判断孤儿），避免重复解析 newBytes。</p>
	 *
	 * <p>本方法使用 {@code ClassReader(bytes).accept(cn, 0)} 完整读取，
	 * 因为要写出合法的 Class 文件必须保留 StackMapTable 帧数据。</p>
	 *
	 * <p>整体流程：</p>
	 * <ol>
	 *   <li>读入 {@link ClassNode}。</li>
	 *   <li>通过 {@link ClassRemapper} 应用方法名与字符串常量的重命名。</li>
	 *   <li>对 {@code $deserializeLambda$} 做 {@code lookupswitch} 的 key 后处理。</li>
	 *   <li>返回 remapped 后的节点，由调用方写出。</li>
	 * </ol>
	 */
	private static ClassNode applyTransform(byte[] bytes, MatchContext ctx) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);

		ClassNode remapped = new ClassNode();
		Remapper remapper = new Remapper(Opcodes.ASM9) {
			@Override
			public String mapMethodName(String owner, String name, String desc) {
				if (owner.equals(ctx.currentClass)) {
					String nn = ctx.renameMap.get(name + desc);
					if (nn != null) return nn;
				}
				return name;
			}

			@Override
			public Object mapValue(Object value) {
				if (value instanceof String s && MethodFingerprinter.isSyntheticName(s)) {
					// 同名歧义时不动字符串常量，避免误改
					if (!ctx.ambiguousNames.contains(s)) {
						String nn = ctx.renameBySimpleName.get(s);
						if (nn != null) return nn;
					}
				}
				return super.mapValue(value);
			}
		};
		cn.accept(new ClassRemapper(remapped, remapper));

		// 后处理：$deserializeLambda$ 里的 lookupswitch hash key 必须跟着字符串常量一起更新
		for (MethodNode mn : remapped.methods) {
			if ("$deserializeLambda$".equals(mn.name)) {
				fixDeserializeSwitch(mn);
			}
		}
		return remapped;
	}

	/** 把 {@link ClassNode} 序列化为字节码。 */
	private static byte[] writeClass(ClassNode cn) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return cw.toByteArray();
	}

	/** 收集一个类里所有方法的 {@code name + desc}。 */
	private static Set<String> collectMethodKeys(ClassNode cn) {
		Set<String> keys = new HashSet<>(cn.methods.size() * 2);
		for (MethodNode mn : cn.methods) keys.add(mn.name + mn.desc);
		return keys;
	}

	/**
	 * 重建 {@code $deserializeLambda$} 中 {@code String.hashCode()} 分派的
	 * {@code lookupswitch} 键值。
	 *
	 * <p>javac 生成的模式：</p>
	 * <pre>
	 *   s.hashCode() -> LOOKUPSWITCH
	 *     Label_1: LDC "lambda$foo$1"; s.equals(...); ...
	 *     Label_2: LDC "lambda$foo$2"; s.equals(...); ...
	 * </pre>
	 *
	 * <p>重命名器会改写 LDC 字符串，但 int 型 key 是常量，不会被自动更新，
	 * 因此必须重新计算并按升序重排。</p>
	 *
	 * <p><b>容错策略</b>：某个 case 块内找不到“符合合成名特征的 implMethodName
	 * 字面量”时，<b>沿用该 case 的原 key</b>，而不是放弃整个 switch 修复。
	 * 这保证了“同时包含方法引用（如 {@code "trim"}）和 lambda 的类”也能被
	 * 正确对齐——方法引用名不会被 Remapper 改写，其原 key 天然正确。</p>
	 *
	 * <p>重算后若 key 之间发生碰撞，才放弃整个 switch 的改写，并打一条警告。
	 * 不抛异常的原因：与其让整次热更失败，不如保留原 switch（对没被改名的
	 * lambda 仍然可用）。</p>
	 */
	private static void fixDeserializeSwitch(MethodNode mn) {
		for (AbstractInsnNode n : mn.instructions.toArray()) {
			if (!(n instanceof LookupSwitchInsnNode sw)) continue;
			if (!isStringHashSwitch(sw)) continue;

			// 边界：本 switch 的所有 case label 以及 default label
			Set<LabelNode> boundaries = new HashSet<>(sw.labels);
			if (sw.dflt != null) boundaries.add(sw.dflt);

			int   size    = sw.labels.size();
			int[] newKeys = new int[size];
			for (int i = 0; i < size; i++) {
				String s = uniqueStringLdcInBlock(sw.labels.get(i), boundaries);
				if (s != null) {
					newKeys[i] = s.hashCode();
				} else {
					// 块内没有可识别的合成方法名 —— 沿用原 key。
					// 常见于：方法引用（"trim" / "doWork"）或描述符（"()V"）之类
					// 不会被 Remapper 改写的字符串，其 hash 保持不变，原 key 天然有效。
					newKeys[i] = sw.keys.get(i);
				}
			}

			// 碰撞检测：重算后的 key 不允许重复
			Set<Integer> seen = new HashSet<>();
			boolean      ok   = true;
			for (int k : newKeys) {
				if (!seen.add(k)) { ok = false; break; }
			}
			if (!ok) {
				warnSkipSwitch(mn, "重算后出现 hashCode 碰撞");
				continue;
			}

			// 按新 key 升序重排 labels（lookupswitch 的 key 必须升序）
			Integer[] order = new Integer[size];
			for (int i = 0; i < size; i++) order[i] = i;
			final int[] nk = newKeys;
			Arrays.sort(order, Comparator.comparingInt(i -> nk[i]));

			List<LabelNode> newLabels  = new ArrayList<>(size);
			List<Integer>   newKeyList = new ArrayList<>(size);
			for (int i : order) {
				newLabels.add(sw.labels.get(i));
				newKeyList.add(newKeys[i]);
			}
			sw.labels = newLabels;
			sw.keys   = newKeyList;
		}
	}

	/**
	 * 判断一个 {@link LookupSwitchInsnNode} 是否是 {@code String.hashCode()} 分派。
	 * <p>启发式规则：其前一条有效指令为 {@code java/lang/String.hashCode()I}。</p>
	 */
	private static boolean isStringHashSwitch(LookupSwitchInsnNode sw) {
		AbstractInsnNode p = sw.getPrevious();
		while (p != null && (p.getOpcode() < 0 || p.getOpcode() == Opcodes.NOP)) {
			p = p.getPrevious();
		}
		if (!(p instanceof MethodInsnNode mi)) return false;
		return "java/lang/String".equals(mi.owner)
			&& "hashCode".equals(mi.name)
			&& "()I".equals(mi.desc);
	}

	/**
	 * 从 case 块中提取“implMethodName 比较用的”字符串常量。
	 *
	 * <p><b>定位规则</b>：扫描块内所有 {@code java/lang/String.equals} 调用，
	 * 只看与它关联的 LDC 参数（见下），且该字符串必须满足
	 * {@link MethodFingerprinter#isSyntheticName}。这会过滤掉：</p>
	 * <ul>
	 *   <li>ECJ 单 switch 布局下 {@code getFunctionalInterfaceClass} /
	 *       {@code getImplMethodSignature} 比较用的 {@code "com/foo/Bar"}、
	 *       {@code "()V"} 等字符串；</li>
	 *   <li>方法引用名（如 {@code "trim"}）—— 它们不会被 Remapper 改写，
	 *       原 key 天然正确，无需重算。</li>
	 * </ul>
	 *
	 * <p><b>两种字节码形态</b>都要覆盖：</p>
	 * <ol>
	 *   <li><b>标准形式</b>{@code s.equals("const")}：栈序 {@code [ALOAD, LDC]}，
	 *       则 equals 的直接前驱就是 LDC。</li>
	 *   <li><b>反向形式</b>{@code "const".equals(s)}：栈序 {@code [LDC, ALOAD]}，
	 *       则 equals 的前驱是 ALOAD，需要再往前一步才是 LDC。
	 *       某些字节码优化器或非标准编译产出会出现这种形态。</li>
	 * </ol>
	 *
	 * <p>注意 ASM 在读取时会把 {@code aload_0}~{@code aload_3} 这类无操作数形式
	 * 归一化为 {@code visitVarInsn(ALOAD, n)}，因此只需判断
	 * {@code VarInsnNode} 且 {@code getOpcode() == Opcodes.ALOAD} 即可覆盖全部
	 * 变量加载变体。</p>
	 *
	 * <p>块内若出现两个 <b>不同</b> 的合成名字符串（hash 碰撞导致的合并 case），
	 * 返回 {@code null} 由调用方沿用原 key；只出现一个合成名时返回它。</p>
	 *
	 * <p><b>终止条件</b>：</p>
	 * <ol>
	 *   <li>遇到 {@code boundaries} 中任一标签（本 switch 的其它 case 或 default）。</li>
	 *   <li>遇到无条件跳转或返回指令 —— 当前 case 逻辑已终结，后续指令属于其它
	 *       基本块，不应计入。若缺此截断，“最后一个 case”会一路穿透到
	 *       {@code $deserializeLambda$} 尾部的 LDC，使统计结果错误。</li>
	 *   <li>指令流自然结束。</li>
	 * </ol>
	 */
	private static String uniqueStringLdcInBlock(LabelNode start, Set<LabelNode> boundaries) {
		String found = null;
		for (AbstractInsnNode n = start.getNext(); n != null; n = n.getNext()) {
			if (n instanceof LabelNode l && boundaries.contains(l)) break;

			if (n instanceof MethodInsnNode mi
				&& "java/lang/String".equals(mi.owner)
				&& "equals".equals(mi.name)
				&& "(Ljava/lang/Object;)Z".equals(mi.desc)) {

				AbstractInsnNode prev = previousRealInsn(mi);

				// 反向形式 "const".equals(s)：前一条是 ALOAD，再往前才是 LDC。
				// 标准形式 s.equals("const")：前一条就是 LDC，不做这一跳。
				// ASM 会把 aload_0~aload_3 归一化为 VarInsnNode(ALOAD, n)，
				// 所以只需判断 opcode == ALOAD 就能覆盖全部变体。
				if (prev instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD) {
					prev = previousRealInsn(prev);
				}

				if (prev instanceof LdcInsnNode ldc
					&& ldc.cst instanceof String s
					&& MethodFingerprinter.isSyntheticName(s)) {
					if (found == null) {
						found = s;
					} else if (!found.equals(s)) {
						// 块内出现两个不同的合成名（hash 碰撞合并 case），
						// 保守返回 null，由调用方沿用原 key。
						return null;
					}
				}
			}

			// 遇到无条件跳转或返回指令：当前 case 逻辑已终结，立即截断
			int op = n.getOpcode();
			if (op == Opcodes.GOTO
				|| op == Opcodes.RETURN
				|| op == Opcodes.IRETURN || op == Opcodes.LRETURN
				|| op == Opcodes.FRETURN || op == Opcodes.DRETURN
				|| op == Opcodes.ARETURN
				|| op == Opcodes.ATHROW) {
				break;
			}
		}
		return found;
	}

	/**
	 * 跳过 {@link FrameNode} / {@link LabelNode} / {@link LineNumberNode}
	 * 以及插桩工具插入的 {@code NOP}，返回前一条真正的指令节点；
	 * 没有则返回 {@code null}。
	 *
	 * <p>与 {@link #isStringHashSwitch} 内部的过滤保持对称：都同时跳过
	 * “伪节点（opcode &lt; 0）”和 {@code NOP}。</p>
	 */
	private static AbstractInsnNode previousRealInsn(AbstractInsnNode n) {
		AbstractInsnNode p = n.getPrevious();
		while (p != null && (p.getOpcode() < 0 || p.getOpcode() == Opcodes.NOP)) {
			p = p.getPrevious();
		}
		return p;
	}

	/** 放弃修复时留痕，避免“静默失效”。 */
	private static void warnSkipSwitch(MethodNode mn, String reason) {
		HotSwapAgent.info("[LambdaAligner] 跳过 $deserializeLambda$ 的 switch 修复（"
			+ mn.name + mn.desc + "）：" + reason);
	}

	/**
	 * 扫描字节码并收集合成方法信息，返回 {@link ClassNode}。
	 *
	 * <p><b>无条件</b>把所有方法（含构造函数、静态块、桥接、普通业务方法、
	 * 合成方法）的 {@code name}/{@code name+desc} 分别登记到
	 * {@link MatchContext#existingNewNames} / {@link MatchContext#oldNameDescSet}，
	 * 作为 fresh name 的避障集，避免生成的新名与类中已有的普通方法签名碰撞
	 * （会导致 {@code ClassFormatError: Duplicate method name&signature}）。</p>
	 *
	 * <p>随后跳过：构造函数、静态初始化块、桥接方法，以及
	 * {@link MethodFingerprinter#isExcluded} 列出的方法（如
	 * {@code $deserializeLambda$}）。</p>
	 *
	 * <p>返回的 {@link ClassNode} 使用 {@code SKIP_DEBUG | SKIP_FRAMES} 读取，
	 * 因此<b>不能直接写回字节码</b>——它被用于：
	 * （a）取 {@code name}；（b）在 {@code renameMap} 为空时供调用方收集
	 * {@code presentKeys}（只需 {@code name + desc}）；
	 * （c）供 {@link #resurrectOrphanedLambdas} 提取孤儿方法的签名头，
	 * 避免再次读取 {@code oldBytes}。</p>
	 *
	 * @param bytes 要扫描的字节码
	 * @param ctx   匹配上下文
	 * @param isOld 是否为旧版本
	 * @return 该类的 {@link ClassNode}
	 */
	private static ClassNode scan(byte[] bytes, MatchContext ctx, boolean isOld) {
		ClassNode cn = new ClassNode();
		// SKIP_DEBUG：不解析行号 / 局部变量表；SKIP_FRAMES：不解析 StackMapTable。
		// 二者对逻辑指纹都没有贡献，跳过可减少内存与解析时间。
		new ClassReader(bytes).accept(cn,
			ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

		for (MethodNode mn : cn.methods) {
			// —— 无条件登记到避障集：让 fresh name 不会撞到类中任何已有方法 ——
			if (isOld) ctx.oldNameDescSet.add(mn.name + mn.desc);
			else       ctx.existingNewNames.add(mn.name);

			if (mn.name.startsWith("<")) continue;
			if ((mn.access & Opcodes.ACC_BRIDGE) != 0) continue;
			if (MethodFingerprinter.isExcluded(mn.name)) continue;

			boolean isSynthetic    = (mn.access & Opcodes.ACC_SYNTHETIC) != 0;
			boolean matchesPattern = MethodFingerprinter.isSyntheticName(mn.name);
			if (!isSynthetic && !matchesPattern) continue;

			MethodFingerprinter fp = ctx.fingerprinter;
			fp.reset();
			fp.setContext(cn.name);
			fp.setValidLabels(collectValidLabels(mn));

			// 回放指令流；传入的 Label 与 collectValidLabels 取出的实例一致
			mn.accept(fp);

			String  logicalName = extractLogicalName(mn.name);
			// access$ 是跨类引用，本对齐器只做“保名不改名”。
			//
			// 这里进一步收窄为"必须命中 lambda 系名字模式"，而不只是"带 ACC_SYNTHETIC"。
			// 原因：本对齐器改名字时只重写**本类内部**对它的引用（见 applyTransform 的
			// mapMethodName），别的类里对该方法的调用不会跟着改。而 ACC_SYNTHETIC 覆盖的
			// 远不止 lambda —— Kotlin 的 foo$default / getX$annotations、编译器生成的各种
			// 桥接桩都带这个标志，它们经常体相同（例如一堆空体的 $annotations）。
			// 一旦让它们参与匹配，跨组指纹匹配就会把"新增的那个同体合成方法"改名成
			// "被删的那个的名字"，而外部调用点仍指向新名字 —— 直接 NoSuchMethodError。
			// 实测复现见 scratch/hstest/kt/：getBaz$annotations 被改成了 getFoo$annotations。
			//
			// 收窄后这些方法只登记进避障集（scan 开头无条件登记 name / name+desc），
			// 阶段二生成避障名时仍会避开它们，但不再参与任何匹配与改名。
			boolean renameable  = matchesPattern && !mn.name.startsWith("access$");
			SyntheticInfo info  = new SyntheticInfo(
				mn.name, mn.desc, mn.access, fp.getHash(), logicalName, renameable);
			// 幽灵空壳（上一轮为兜住老 CallSite 而注入的空方法）标记为"不参与匹配"：
			// 它必须留在 oldGroups 里供孤儿计算复现，但名字已经是"死名字"，
			// 不能再被当成某个新 lambda 的目标 —— 否则会把它挤到别的名字上去。
			info.ghost = isGhostMethod(mn);
			info.children = collectChildLambdaNames(mn, cn.name);
			if (!isOld) putNameIndex(ctx, ctx.childIndex, info);
			// 完整名字索引（两侧各一份），供 infoByName 做 O(1) 查找。
			// 这里就地填充即可：scan 的循环体已经走完该名字对应的 SyntheticInfo 构建。
			putNameIndex(ctx, isOld ? ctx.oldNameIndex : ctx.newNameIndex, info);
			groupByLogic(isOld ? ctx.oldGroups : ctx.newGroups, info, cn.name);
		}

		// 子指纹必须在整类扫描完之后再算：子方法的 SyntheticInfo 要先存在。
		LongObjectMap<List<SyntheticInfo>> groups = isOld ? ctx.oldGroups : ctx.newGroups;
		for (int idx = groups.nextEntry(-1); idx != -1; idx = groups.nextEntry(idx)) {
			List<SyntheticInfo> g = groups.valueAt(idx);
			if (g == null) continue;
			for (SyntheticInfo info : g) {
				if (info.children.isEmpty()) continue;

				// 新类侧额外登记"子名 -> SyntheticInfo"，供 calleesPairTo 查"子配给了谁"
				if (!isOld) {
					List<SyntheticInfo> cis = new ArrayList<>(info.children.size());
					for (String c : info.children) cis.add(ctx.childIndex.get(c));
					info.childInfos = cis;
				}
			}
		}

		// 子树形状：由子形状串接而成（与内容无关），迭代到定稿。
		//
		// groups 的遍历顺序不确定，父可能先于子被处理；此时**读到未定稿的子就跳过本轮**，
		// 绝不用哨兵值冒充叶子。收敛判据是"没有未定稿 且 无变化"。
		//
		// 性能：串接缓冲在**循环外**分配一次、每轮复用，代替原先每轮每方法的
		// `new ArrayList` + `String.join`。
		//
		// 说明：子形状**必须排序**后才能比较（否则同一形状因子顺序不同而被判为不同，
		// 收敛判据失效）；因此排序无法跳过，能省的是"串接与 String 生成"——
		// 与既有值相同就**不生成 String**。
		//
		// 用 StringBuilder 而非 LookupKey：这里每次都要先 append 再比较，
		// LookupKey 的缓存 hash 必然失效、用不上；它唯一多出的能力
		//（equals 支持 CharSequence 比较）由 {@link String#contentEquals(CharSequence)} 提供。
		// LookupKey 适合"稳定 key 反复查哈希表"的场景，不是这里。
		StringBuilder shapeBuf = new StringBuilder(64);
		for (int round = 0; round < 64; round++) {
			boolean changed = false, pending = false;
			for (int idx = groups.nextEntry(-1); idx != -1; idx = groups.nextEntry(idx)) {
				List<SyntheticInfo> g = groups.valueAt(idx);
				if (g == null) continue;
				for (SyntheticInfo info : g) {
					List<String> shapes = new ArrayList<>(info.children.size());
					boolean ready = true;
					for (String c : info.children) {
						SyntheticInfo ci = infoByName(ctx, isOld, c);
						if (ci == null || ci.ghost) { shapes.add("()"); continue; }   // 幽灵 = 无子
						if (ci.shape == null) { ready = false; break; }               // 子未定稿
						shapes.add(ci.shape);
					}
					if (!ready) { pending = true; continue; }
					Collections.sort(shapes);

					shapeBuf.setLength(0);
					shapeBuf.append('(');
					for (int i = 0; i < shapes.size(); i++) shapeBuf.append(shapes.get(i));
					shapeBuf.append(')');

					// 轻量短路：与既有值相同则不生成 String。
					// contentEquals 在 String 一侧调用，参数是 CharSequence（StringBuilder 满足）。
					if (info.shape != null && info.shape.length() == shapeBuf.length()
						&& info.shape.contentEquals(shapeBuf)) {
						continue;
					}
					info.shape = shapeBuf.toString();
					changed = true;
				}
			}
			if (!changed && !pending) break;
			if (round == 63) {
				// 明确区分"没算完"与"算完了"：这里留下的是**未定稿**（形状可能为 null），
				// 而不是一个看似合法的错值。
				HotSwapAgent.warn("[LambdaAligner] 子树形状在 " + (isOld ? "old" : "new")
					+ " 侧 64 轮未收敛（存在环或异常引用），沿用当前值；未定稿者保持 null");
			}
		}
		// 仍为 null 的（理论上只在成环时出现）退化为叶子形状，保证后续比较不 NPE。
		for (int idx = groups.nextEntry(-1); idx != -1; idx = groups.nextEntry(idx)) {
			List<SyntheticInfo> g = groups.valueAt(idx);
			if (g == null) continue;
			for (SyntheticInfo info : g) if (info.shape == null) info.shape = "()";
		}

		// 上行深度：该 lambda 被多少层 lambda 嵌套引用。
		//
		// 由**反向** indy 引用关系算出（谁的体内引用了谁），迭代到定稿。
		//   • 被非 lambda 方法（如 build()）引用 -> 深度 0；
		//   • 只被 lambda 引用 -> 1 + 那些引用者的深度取最大；
		//   • 幽灵**不得当引用者**（它们是空壳，没有真实语义），也不参与。
		//
		// 与 shape 一样，这是纯结构信息、与方法体内容无关，因此"编辑叶子体"不影响它。
		// 哨兵 -1 = 未定稿；0 是合法值，绝不用作哨兵。
		{
			// 反向索引：target -> referrers（仅本侧、排除幽灵）
			Map<String, List<String>> referrers = new HashMap<>();
			for (int idx = groups.nextEntry(-1); idx != -1; idx = groups.nextEntry(idx)) {
				List<SyntheticInfo> g = groups.valueAt(idx);
				if (g == null) continue;
				for (SyntheticInfo info : g) {
					if (info.ghost) continue;                       // 幽灵不是引用者
					for (String c : info.children) {
						referrers.computeIfAbsent(c, k -> new ArrayList<>()).add(info.name);
					}
				}
			}
			for (int round = 0; round < 64; round++) {
				boolean changed = false, pending = false;
				for (int idx = groups.nextEntry(-1); idx != -1; idx = groups.nextEntry(idx)) {
					List<SyntheticInfo> g = groups.valueAt(idx);
					if (g == null) continue;
					for (SyntheticInfo info : g) {
						if (info.ghost) continue;
						List<String> rs = referrers.get(info.name);
						int nd;
						if (rs == null || rs.isEmpty()) {
							// 没有任何 lambda 引用它：若它本身是 lambda 合成方法，
							// 说明被普通方法（build()）直接引用 -> 深度 0。
							nd = 0;
						} else {
							int max = -1;
							boolean pendingRef = false;
							for (String r : rs) {
								SyntheticInfo ri = infoByName(ctx, isOld, r);
								if (ri == null) { pendingRef = true; continue; }
								if (ri.upDepth < 0) { pendingRef = true; continue; }
								if (ri.upDepth > max) max = ri.upDepth;
							}
							if (pendingRef) { pending = true; continue; }   // 引用者未定稿 -> 本轮跳过
							nd = max + 1;
						}
						if (nd != info.upDepth) { info.upDepth = nd; changed = true; }
					}
				}
				// 与 computeShapes 一致：收敛判据是"没有未定稿 且 无变化"。
				// 原先只判 changed，于是"引用者未定稿"（成环等）时第一轮即静默 break，
				// upDepth 停在 -1，而那行告警**永远打不出来** —— 未收敛被完全静默。
				if (!changed && !pending) break;
				if (round == 63) {
					HotSwapAgent.warn("[LambdaAligner] 上行深度在 " + (isOld ? "old" : "new")
						+ " 侧 64 轮未收敛（可能存在环），未定稿者保持 -1");
				}
			}
		}

		computeSemanticHashes(ctx, groups, isOld, cn.name);

		return cn;
	}

	/**
	 * 递归语义指纹：{@link SyntheticInfo#hash} 再逐层折入子的语义指纹。每轮至少定稿一层，
	 * 最多嵌套深度轮即收敛（循环次数上限只是防御）。
	 *
	 * <p>{@code 0} 表示未定稿，而 {@code 0} 不可能是合法指纹值（CRC64 结果），
	 * 因此不存在"哨兵与合法值撞车"的问题。</p>
	 */
	private static void computeSemanticHashes(MatchContext ctx,
	                                          LongObjectMap<List<SyntheticInfo>> groups,
	                                          boolean isOld, String className) {
		for (int round = 0; round < 64; round++) {
			boolean changed = false;
			for (int idx = groups.nextEntry(-1); idx != -1; idx = groups.nextEntry(idx)) {
				List<SyntheticInfo> g = groups.valueAt(idx);
				if (g == null) continue;
				for (SyntheticInfo info : g) {
					long sem = info.hash;
					for (String c : info.children) {
						SyntheticInfo ci = infoByName(ctx, isOld, c);
						if (ci == null) continue;
						// 幽灵是空壳，**没有语义**：不能把它的 hash 折进父的语义指纹。
						// 折进去的后果（实测，save3 两轮序列）：旧的父拿到一个无意义的语义指纹，
						// 于是能与"本不该配"的新方法碰巧等价，外层/中层被静默错绑。
						// 幽灵仍留在 groups 里供孤儿计算复现，但语义指纹计算把它当"无此子"。
						if (ci.ghost) continue;
						long cs = ci.semanticHash != 0 ? ci.semanticHash : ci.hash;
						if (ci.semanticHash == 0 && !ci.children.isEmpty()) { sem = 0; break; }
						sem = Utils.compositeHash(Long.toString(sem), Long.toString(cs));
					}
					if (sem != 0 && sem != info.semanticHash) {
						info.semanticHash = sem;
						changed = true;
					}
				}
			}
			if (!changed) break;
			if (round == 63) {
				// 跑满上限仍未收敛：降级为"沿用当前值并留痕"，绝不抛异常。
				// 正常 javac 产物不会成环（lambda 的构造关系是 DAG），这里只是防御：
				// 真出现环时，宁可让少数方法按当前（可能不完整的）语义指纹参与匹配，
				// 也不能让整次热更失败。
				HotSwapAgent.warn("[LambdaAligner] 递归语义指纹在 " + className
					+ " 上 64 轮未收敛，沿用当前值继续（结果可能不够精确，但不影响可用性）");
			}
		}
	}

	/**
	 * 向名字索引中登记 SyntheticInfo。
	 *
	 * <p><b>活方法优先于幽灵</b>：同名不同描述符的幽灵与活方法共存时（如上一轮对齐注入的幽灵），
	 * 活方法必须优先占住名字索引，不能被后追加的幽灵覆盖；若幽灵先被扫描到，后来的活方法也必须覆盖它。
	 * 这保证 {@link #infoByName} 与子引用解析始终拿到真正的活方法，避免语义指纹计算漏折入子节点。</p>
	 *
	 * <p>两个都是活方法的同名不同描述符：仍后写覆盖，并在 DEBUG 下留痕。</p>
	 */
	private static void putNameIndex(MatchContext ctx, Map<String, SyntheticInfo> index, SyntheticInfo info) {
		SyntheticInfo prev = index.get(info.name);
		if (prev == null) {
			index.put(info.name, info);
		} else if (prev.ghost && !info.ghost) {
			// 已有幽灵，后遇到活方法：活方法覆盖幽灵
			index.put(info.name, info);
		} else if (!prev.ghost && info.ghost) {
			// 已有活方法，后遇到幽灵：保留活方法，不被幽灵覆盖
		} else {
			if (!prev.ghost && !info.ghost && !prev.desc.equals(info.desc)) {
				dbg(() -> "COLLISION: same-name live methods with different desc: " + info.name
					+ " " + prev.desc + " vs " + info.desc);
			}
			index.put(info.name, info);
		}
	}

	/** 在已扫描的旧/新分组里按名字找 SyntheticInfo（供子指纹计算使用）。 */
	private static SyntheticInfo infoByName(MatchContext ctx, boolean isOld, String name) {
		// O(1) 查索引。早先是"遍历所有 group 的所有成员"的双重循环，而本方法被
		// **三个 64 轮定稿循环**按"每个方法 × 每个子"调用，最坏合计 O(64·N²)。
		// 见 MatchContext.oldNameIndex / newNameIndex 的说明。
		return (isOld ? ctx.oldNameIndex : ctx.newNameIndex).get(name);
	}

	/**
	 * 收集逻辑相关的 Label：只包含被跳转指令、switch、异常表引用的 Label。
	 *
	 * <p>排除纯调试标签（行号锚点、局部变量作用域边界），这些标签在不同
	 * {@code -g} 设置下数量与位置都会变，不应参与指纹。</p>
	 *
	 * <p>返回的 {@link Label} 实例与 {@link MethodNode#accept(MethodVisitor)}
	 * 回放时传入的是同一对象，可直接用作集合元素。</p>
	 */
	private static Set<Label> collectValidLabels(MethodNode mn) {
		Set<Label> valid = new HashSet<>();
		for (AbstractInsnNode insn : mn.instructions) {
			if (insn instanceof JumpInsnNode j) {
				valid.add(j.label.getLabel());
			} else if (insn instanceof TableSwitchInsnNode t) {
				valid.add(t.dflt.getLabel());
				for (LabelNode l : t.labels) valid.add(l.getLabel());
			} else if (insn instanceof LookupSwitchInsnNode l) {
				valid.add(l.dflt.getLabel());
				for (LabelNode ln : l.labels) valid.add(ln.getLabel());
			}
		}
		if (mn.tryCatchBlocks != null) {
			for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
				valid.add(tcb.start.getLabel());
				valid.add(tcb.end.getLabel());
				valid.add(tcb.handler.getLabel());
			}
		}
		return valid;
	}

	/**
	 * 将被删除的 lambda 以“空方法体”的形式复活注入到新字节码中，
	 * 防止被缓存的 {@code CallSite} 抛出 {@link NoSuchMethodError}。
	 *
	 * <p>复用调用方已经解析好的 {@code oldCn} 提取孤儿方法的签名头，
	 * 避免再次读取 {@code oldBytes}。</p>
	 *
	 * <p>{@code oldCn} 是用 {@code SKIP_DEBUG | SKIP_FRAMES} 读入的，
	 * 但注入空壳只用到 {@code access} / {@code name} / {@code desc} /
	 * {@code signature} / {@code exceptions} 这些方法头字段——它们不受
	 * {@code SKIP_*} 影响。方法体（Code 属性）由 {@link #injectDummyBody}
	 * 重新生成，不会拷贝 {@code oldCn} 中的原始指令。</p>
	 *
	 * @param oldCn       已解析的旧类节点（由 {@code align} 中的 {@code scan} 提供）
	 * @param newBytes    经过重命名处理后的新版本字节码
	 * @param presentKeys 新版本里实际存在的所有方法键（{@code name + desc}）
	 * @param ctx         当前匹配上下文
	 * @return 注入幽灵方法后的最终字节码；若无孤儿则原样返回 {@code newBytes}
	 */
	private static byte[] resurrectOrphanedLambdas(ClassNode oldCn, byte[] newBytes,
	                                               Set<String> presentKeys, MatchContext ctx) {
		// 1. 旧类里的合成方法键集合，差集就是孤儿
		Set<String> orphanedKeys = new HashSet<>();
		var         oldGroups    = ctx.oldGroups;
		for (int idx = oldGroups.nextEntry(-1); idx != -1; idx = oldGroups.nextEntry(idx)) {
			List<SyntheticInfo> oldGroup = oldGroups.valueAt(idx);
			if (oldGroup == null) continue;
			for (SyntheticInfo oi : oldGroup) {
				String key = oi.name + oi.desc;
				if (!presentKeys.contains(key)) orphanedKeys.add(key);
			}
		}
		if (orphanedKeys.isEmpty()) return newBytes;

		// 2. 从已解析的 oldCn 中取遗弃方法的签名头（不再二次读 oldBytes）
		//
		// 判据必须是 {@code name + desc}，<b>不能只看名字</b>。同名不同描述符是幽灵的
		// 正常工作形态：捕获列表变化时（旧 {@code lambda$build$0(I)V} → 新
		// {@code lambda$build$0()V}）新方法保住了名字但描述符变了，旧 CallSite 要的正是
		// 旧描述符，全局唯一能兜住它的就是这个幽灵。JVM 按"名字+描述符"解析，两者并存完全合法。
		// 若按名字一刀切地不注入，这类场景会直接 NoSuchMethodError —— 在业务代码路径下就是崩溃，
		// 违背"不崩溃"的设计目标。
		//
		// 真正需要跳过的只有一种：{@code name + desc} 与某个活方法完全相同。
		// 但那已由 orphanedKeys 的定义排除 —— 它取自 {@code !presentKeys.contains(key)}，
		// 而 presentKeys 就是本类的全部方法键。**因此这里不需要再查一次"是否与活方法重名"**：
		// 曾有一份 liveKeys 分支做这件事，它解析的是与 presentKeys 同一份字节码、
		// 结果恒为同一集合，那个判断永远为 false，还白多解析一遍（与"避免二次读取"相悖）。
		List<MethodNode> toInject = new ArrayList<>();
		for (MethodNode mn : oldCn.methods) {
			String key = mn.name + mn.desc;
			if (!orphanedKeys.contains(key)) continue;
			toInject.add(mn);
		}
		if (toInject.isEmpty()) return newBytes;

		// 可观测性：幽灵化是"静默"发生在对齐期的。把这行与
		// warnPositionalMismatch 放在一起看，就能认出"删除 + 改体同时发生"这一形态
		// （两者同时出现时，本类既有被幽灵化的名字、又有位置错配）。
		StringBuilder ghosted = new StringBuilder();
		for (MethodNode mn : toInject) {
			if (ghosted.length() > 0) ghosted.append(", ");
			ghosted.append(mn.name);
		}
		HotSwapAgent.info("[LambdaAligner] " + oldCn.name + " 幽灵化 " + toInject.size()
			+ " 个方法（老 CallSite 将走熔断/降级）：" + ghosted);

		// 3. 以空壳形式追加到新类末尾
		ClassReader cr = new ClassReader(newBytes);
		// 关键：不用 COMPUTE_FRAMES，避免 getCommonSuperClass 触发目标类加载
		ClassWriter  cw        = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
		final String className = cr.getClassName();

		ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
			@Override
			public void visitEnd() {
				for (MethodNode mn : toInject) {
					// 空壳方法必须带 Code 属性，强制清掉 abstract / native
					int access = mn.access & ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
					MethodVisitor dummy = super.visitMethod(
						access, mn.name, mn.desc, mn.signature,
						mn.exceptions == null ? null : mn.exceptions.toArray(new String[0]));
					if (dummy != null) {
						injectDummyBody(dummy, className, mn.name, mn.desc);
					}
				}
				super.visitEnd();
			}
		};
		cr.accept(cv, 0);
		return cw.toByteArray();
	}

	/**
	 * 生成空壳 Lambda 方法体的指令流。
	 * <p>
	 * <b>关键设计（100% 线性无跳转分支，彻底杜绝 VerifyError）</b>：<br>
	 * 字节码中完全不使用任何 {@code IFEQ} / {@code GOTO} 等分支跳转指令和 {@link Label}。
	 * 所有的策略判定、UpdateRef 栈探测、异常抛出与日志去重均下沉到纯 Java 静态方法
	 * {@link #onOrphanInvoked(String)} 中执行。<br>
	 * <ul>
	 *   <li>若为 UpdateRef 保护的调用：Java 静态方法直接抛出 {@link NoSuchMethodError} 展开栈中断执行；</li>
	 *   <li>若为普通业务调用：Java 静态方法去重打印警告日志后正常返回，随后由生成的字节码 100% 线性返回类型默认值（0 / null / false）。</li>
	 * </ul>
	 * 从而在 Java 17+ / 21 等平台环境下，无需 StackMapTable 即可 100% 通过 JVM 类验证。
	 * （本类实际用了 {@code instanceof} 模式匹配与 {@link java.util.stream.Stream#toList()}，
	 * 均需 Java 16+；文首"Java 7+"的旧说法已不成立，{@link StackWalker} 的降级分支因此
	 * 主要是历史兜底而非当前最低版本要求。）
	 * </p>
	 */
	private static void injectDummyBody(MethodVisitor mv, String className, String name, String desc) {
		mv.visitCode();
		Type returnType = Type.getReturnType(desc);

		// 1. 调用纯 Java 静态方法：统一处理策略分发、UpdateRef 栈探测及日志去重。
		//
		// 传入的是**注入时就拼好的 location 常量**（单个 LDC），而不是类名/方法名/描述符三个。
		// 这样运行时热路径上：
		//   • 不分配任何 String（常量字符串的 hash 在 JVM 里已缓存）；
		//   • 集合查找是 O(1) 哈希查找，而非线性扫描；
		//   • 不需要 ThreadLocal 缓冲，也不需要 synchronized。
		mv.visitLdcInsn(locationOf(className, name, desc));
		mv.visitMethodInsn(Opcodes.INVOKESTATIC,
			Type.getInternalName(LambdaAligner.class),
			"onOrphanInvoked", "(Ljava/lang/String;)V", false);

		// 2. 100% 线性无分支返回类型默认值（若上面抛出异常则直接展开调用栈，根本不会执行到此）
		injectDefaultReturnValue(mv, returnType, desc);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	/** location 文本：{@code com.example.Foo#lambda$build$0()V}。注入期算一次，写进字节码常量。 */
	private static String locationOf(String className, String name, String desc) {
		if (className == null || className.isEmpty()) return name + desc;
		return className.replace('/', '.') + '#' + name + desc;
	}

	private static void injectDefaultReturnValue(MethodVisitor mv, Type returnType, String desc) {
		switch (returnType.getSort()) {
			case Type.VOID:
				mv.visitInsn(Opcodes.RETURN);
				break;
			case Type.BOOLEAN:
			case Type.CHAR:
			case Type.BYTE:
			case Type.SHORT:
			case Type.INT:
				mv.visitInsn(Opcodes.ICONST_0);
				mv.visitInsn(Opcodes.IRETURN);
				break;
			case Type.FLOAT:
				mv.visitInsn(Opcodes.FCONST_0);
				mv.visitInsn(Opcodes.FRETURN);
				break;
			case Type.LONG:
				mv.visitInsn(Opcodes.LCONST_0);
				mv.visitInsn(Opcodes.LRETURN);
				break;
			case Type.DOUBLE:
				mv.visitInsn(Opcodes.DCONST_0);
				mv.visitInsn(Opcodes.DRETURN);
				break;
			case Type.OBJECT:
			case Type.ARRAY:
				mv.visitInsn(Opcodes.ACONST_NULL);
				mv.visitInsn(Opcodes.ARETURN);
				break;
			default:
				throw new IllegalStateException("Unhandled return type parsing: " + desc);
		}
	}

	private static final String UPDATE_REF_CLASS = "nipx.ref.UpdateRef";

	/**
	 * 已打印过日志的 location（并发安全）。
	 *
	 * <p>用 {@code ConcurrentHashMap} 支撑：桩代码传来的 location 是**字符串常量**，
	 * 其 hash 在 JVM 中已缓存，因此查找是 O(1)、**不分配**、且无需任何全局锁。</p>
	 */
	private static final Set<String> LOGGED_ORPHANS =
		Collections.newSetFromMap(new ConcurrentHashMap<>());

	/** 日志去重。{@code add} 返回 true 表示本次是首次，才打印。 */
	private static void logOrphanOnce(String location) {
		if (LOGGED_ORPHANS.add(location)) {
			HotSwapAgent.warn("[LambdaAligner] orphaned lambda invoked: " + location
				+ " (subsequent invocations will be muted)");
		}
	}

	/**
	 * 当热重载中被删除/孤立的空壳 Lambda 方法被调用时由生成的字节码调用。
	 * <p>
	 * 所有的策略判定、UpdateRef 栈探测、异常抛出与日志去重均下沉到此 Java 方法中执行，
	 * 从而保证 ASM 生成的字节码 100% 线性无跳转分支，彻底杜绝缺少 StackMapTable 引发的 {@link VerifyError}。
	 * </p>
	 *
	 * <p><b>入参是注入期就拼好的 location 字符串常量</b>（见 {@link #locationOf}），
	 * 而不是类名/方法名/描述符三段：这样运行时热路径上不分配任何 String
	 * （常量字符串的 hash 已被 JVM 缓存），集合查找是 O(1)，且无需 ThreadLocal 或锁。</p>
	 *
	 * <p><b>可见性要求</b>：桩代码通过 {@code INVOKESTATIC} 调用本方法，因此目标类的
	 * 类加载器必须能看到 {@code nipx.LambdaAligner}。父委派正常时成立。</p>
	 *
	 * @param location 形如 {@code com.example.Foo#lambda$build$0()V} 的常量
	 */
	public static void onOrphanInvoked(String location) {
		OrphanPolicy policy = orphanPolicy;

		if (policy == OrphanPolicy.SMART_ADAPTIVE) {
			// ⚠️ 这里**不做"非 UpdateRef"的负缓存**。
			//
			// 曾按 location 缓存"该调用点不是 UpdateRef 发起的"，以省下每次约 1µs 的栈探测。
			// **该缓存不成立**：「是否由 UpdateRef 发起」是**调用上下文**的属性，
			// 不是方法（location）的固有属性。同一个旧 CallSite 完全可能先被业务代码
			// 直接调一次（如初始化期），之后才被注册进 UpdateRef 轮询：
			// 首次判定为"否"后，后续由 UpdateRef 发起的调用会被短路成静默返回，
			// **熔断永不触发** —— 恰好复现本机制要消灭的 60FPS 空转。
			//
			// 因此每次调用都必须真实探测。若将来要优化，正确方向是降低探测本身的成本，
			// 而不是按 location 缓存一个上下文相关的判定。
			if (isCalledByUpdateRef()) {
				throw new NoSuchMethodError("Lambda removed by hot swap: " + location);
			}
			// 普通业务调用：去重后打印日志，随后返回默认值（由外部字节码线性返回）
			logOrphanOnce(location);
			return;
		}

		if (policy == OrphanPolicy.THROW_NO_SUCH_METHOD) {
			throw new NoSuchMethodError("Lambda removed by hot swap: " + location);
		}

		if (policy == OrphanPolicy.THROW) {
			throw new IllegalStateException("Lambda removed by hot swap: " + location);
		}

		if (policy == OrphanPolicy.LOG_AND_RETURN_DEFAULT) {
			// 与 SMART_ADAPTIVE 同款：命中时零 String 分配（见 logOrphanOnce）。
			logOrphanOnce(location);
			return;
		}

		// OrphanPolicy.SILENT: 静默返回
	}

	/**
	 * 判断当前调用是否由 {@link nipx.ref.UpdateRef} 发起（决定是否熔断）。
	 *
	 * <p><b>判据是"幽灵桩的直接调用者是否为 UpdateRef"</b>，而不是"调用栈上某处是否有
	 * UpdateRef"：后者会被无关的同栈帧干扰（例如"业务代码 → UpdateRef → 另一个 lambda"
	 * 这种情形，那个 lambda 其实不是受保护回调）。</p>
	 *
	 * <p><b>为什么不再用 {@code limit(16)} 扫描</b>：那是<b>深度相关</b>的判据 ——
	 * 回调链路上只要夹了较深的用户代码或框架帧（例如嵌套的
	 * {@code Time.run(10, () -> Time.run(5, () -> ...))}），UpdateRef 帧就会被截掉，
	 * 于是误判为"非 UpdateRef"，<b>熔断静默失效</b>。改为"定位幽灵桩入口后取 +2 帧"
	 * 与深度无关：栈有多深都能看到直接调用者。</p>
	 *
	 * <p>降级路径（无 StackWalker 的环境，如 Android/desugar）同样按"定位 {@code onOrphanInvoked}，
	 * 再跳过透明帧取调用者"处理，语义与 StackWalker 路径一致。
	 * 桌面 JVM 上该分支实际不可达（本类最低要求 Java 16+，而 StackWalker 自 Java 9 起可用），
	 * 保留它是为了 Android 等没有 StackWalker 的运行时。</p>
	 *
	 * @return 若直接调用者为 UpdateRef 的 run 系方法（或兜底命中）则返回 true
	 */
	public static boolean isCalledByUpdateRef() {
		try {
			if (StackWalkerHolder.IS_SUPPORTED) {
				// 定位幽灵桩入口
				// 跳过 onOrphanInvoked 自身与桩
				// 穿透代理/隐藏帧
				return Boolean.TRUE.equals(StackWalkerHolder.WALKER.walk(s -> s
				 .dropWhile(f -> !isOrphanEntry(f))        // 定位幽灵桩入口
				 .skip(2)                                  // 跳过 onOrphanInvoked 自身与桩
				 .filter(f -> !isTransparentFrame(f.getClassName()))  // 穿透代理/隐藏帧
				 .findFirst()
				 .map(f -> isUpdateRefInvoke(f.getClassName(), f.getMethodName()))
				 .orElse(false)));
			}
		} catch (Throwable ignored) {}

		try {
			StackTraceElement[] t = new Throwable().getStackTrace();
			int entry = -1;
			for (int i = 0; i < t.length; i++) {
				if (SELF.equals(t[i].getClassName()) && "onOrphanInvoked".equals(t[i].getMethodName())) {
					entry = i;
					break;
				}
			}
			if (entry < 0) return false;
			// 与 StackWalker 路径同构：桩之后跳过透明帧，取第一个真实调用者。
			int skip = entry + 2;   // 跳过 onOrphanInvoked 自身与桩
			while (skip < t.length && isTransparentFrame(t[skip].getClassName())) skip++;
			if (skip >= t.length) return false;
			return isUpdateRefInvoke(t[skip].getClassName(), t[skip].getMethodName());
		} catch (Throwable ignored) {}
		return false;
	}

	/** 本类的全限定名，用于在栈帧里定位幽灵桩入口。 */
	private static final String SELF = LambdaAligner.class.getName();

	/** 该帧是否为幽灵桩入口（本类的 {@code onOrphanInvoked}）。 */
	private static boolean isOrphanEntry(StackWalker.StackFrame f) {
		return SELF.equals(f.getClassName()) && "onOrphanInvoked".equals(f.getMethodName());
	}

	/**
	 * 透明帧：桩与真实调用者之间由 {@code invokedynamic} 生成的代理类帧。
	 *
	 * <p>必须穿透，否则"固定 +2"在两种环境下会静默失效，而后果正是本机制要消灭的
	 * （熔断不触发）：</p>
	 * <ul>
	 *   <li>开了 {@code -XX:+ShowHiddenFrames} 时，{@code Foo$$Lambda/0x...} 会出现在栈里；</li>
	 *   <li>Android/desugar 之后的 {@code -$$Lambda$} 类不是隐藏类，在
	 *       {@code getStackTrace} 里本来就是可见帧。</li>
	 * </ul>
	 *
	 * <p>这是<b>针对性放行</b>，不是通用扫描：只认类名里带 lambda 代理标记的帧，
	 * 不会把"lambda 本身被 UpdateRef 持有"的严格判据放宽。</p>
	 */
	private static boolean isTransparentFrame(String className) {
		if (className == null || className.isEmpty()) return false;
		// 刻意只用**类名**（不取 Class 对象），因此无需 RETAIN_CLASS_REFERENCE，
		// 也避免为判断而触发类加载。
		return className.indexOf("$$Lambda") >= 0
			|| className.indexOf("$$$Lambda") >= 0
			|| className.startsWith("jdk.internal.reflect.");
	}

	/**
	 * 该帧是否看起来是 UpdateRef 的回调执行点。
	 *
	 * <p>只做<b>精确</b>类名匹配：直接调用者必然是 {@code UpdateRef} 自己的方法，
	 * 不存在内部类的场景，因此不需要内部类前缀匹配 —— 那会连带匹配
	 * {@code UpdateRef$...}，属于不必要的放宽。</p>
	 */
	private static boolean isUpdateRefInvoke(String className, String methodName) {
		return UPDATE_REF_CLASS.equals(className) && methodName.startsWith("run");
	}

	/**
	 * 延迟初始化 Holder 类，避免在缺少 StackWalker 的低版本环境中触发 ClassNotFoundException / NoClassDefFoundError。
	 */
	private static class StackWalkerHolder {
		static final boolean     IS_SUPPORTED;
		static final StackWalker WALKER;

		static {
			boolean     supported = false;
			StackWalker walker    = null;
			try {
				walker = StackWalker.getInstance();
				supported = true;
			} catch (Throwable ignored) {}
			IS_SUPPORTED = supported;
			WALKER = walker;
		}
	}
	//endregion

	//region 辅助方法

	/**
	 * 识别"幽灵空壳"方法：上一轮为兜住被删除 lambda 的老 {@code CallSite} 而注入的空方法。
	 *
	 * <p>它调用 {@link #onOrphanInvoked} 来完成策略分发，所以只凭这一个特征就能认出，
	 * 不需要自定义 attribute。识别出的方法会：
	 * <ul>
	 *   <li><b>保留</b>在 {@link MatchContext#oldGroups} 里 —— 孤儿计算要靠它继续复现注入，
	 *       否则下一轮它就从类里消失了，老 {@code CallSite} 会变成 {@link NoSuchMethodError} 之外的
	 *       更糟形态；</li>
	 *   <li><b>不参与任何匹配</b>（见 {@link SyntheticInfo#ghost}）—— 它的名字是死名字，
	 *       只能被兜住，不能被某个新 lambda "认领"，否则会把本该活着的 lambda 挤到别的名字上。</li>
	 * </ul>
	 *
	 * <p>实测动机（{@code scratch/hstest/del/}）：V1 两个 lambda、V2 删掉第一个（留下幽灵
	 * {@code $0}）、V3 改剩下那个的方法体。修复前 V3 会对齐成
	 * {@code $0=[新体]}、{@code $1=幽灵} —— 活着的 lambda 丢了它自己的名字 {@code $1}，
	 * 持有 {@code $1} 的调用点转而执行新代码。幽灵退出匹配后，它会稳定保持在
	 * {@code $1} 这个名字下继续当空壳。</p>
	 */
	private static boolean isGhostMethod(MethodNode mn) {
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof MethodInsnNode mi
				&& mi.owner.equals(Type.getInternalName(LambdaAligner.class))
				&& mi.name.equals("onOrphanInvoked")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 判断两个合成方法的签名是否<b>逻辑等价</b>。
	 *
	 * <p>背景：同一段 lambda 体，在不同编译/变换轮次里可能以两种形态出现——</p>
	 * <ul>
	 *   <li><b>实例方法</b>：{@code private void lambda$build$19(ScrollPane, Floatc, Cell)}，
	 *       {@code this} 是隐式的接收者；</li>
	 *   <li><b>静态方法</b>：{@code private static void lambda$build$19(Tester, ScrollPane, Floatc, Cell)}，
	 *       {@code this} 变成显式的首参数（{@link AnnotationTransformer#forceStaticLambdas} 的产物）。</li>
	 * </ul>
	 *
	 * <p>两者描述的<b>是同一件事</b>。若按“{@code static} 位必须相同 + 描述符必须逐字符相同”
	 * 去比对，就会把同一个 lambda 判成“老方法消失、新方法新增”，进而在阶段二生成
	 * {@code lambda$build$21} 这类避障名，让 JVM 里仍持有老 {@code CallSite} 的 UI 监听器
	 * 抛出 {@link NoSuchMethodError}。</p>
	 *
	 * <p>因此这里把实例方法的隐式 {@code this} 前置成显式首参数后，再逐字符比对。注意比对
	 * 使用的是<b>带 owner 的完整参数类型</b>，所以只有当静态版本的首参数恰好就是
	 * {@code L<当前类>;}（即 {@code forceStaticLambdas} 的既定形态）时才判定等价，
	 * 不会把“首参数恰好是别的类”的错误签名误判为等价。</p>
	 *
	 * <p><b>边界（诚实说明）</b>：本方法只抹平 {@code static} 位与 {@code this} 形态的差异，
	 * 不抹平“捕获列表本身发生变化”导致的描述符差异（例如新版本少捕获了一个局部变量）。
	 * 那种情况需要真正的方法适配器（老签名 → 新签名的桥接转发），属于独立议题；
	 * 本方法至少保证“名字被保住”，不再退化成避障名。</p>
	 *
	 * @param owner 当前类的内部名（形如 {@code com/example/Foo}）
	 * @param a     参与比对的方法信息
	 * @param b     参与比对的方法信息
	 * @return 两者签名逻辑等价时返回 {@code true}
	 */
	private static boolean isSignatureCompatible(String owner, SyntheticInfo a, SyntheticInfo b) {
		boolean sa = a.isStatic();
		boolean sb = b.isStatic();
		if (sa == sb) return a.desc.equals(b.desc);
		if (owner == null) return false;
		return normalizeStaticShape(owner, sa, a.desc).equals(normalizeStaticShape(owner, sb, b.desc));
	}

	/**
	 * 把描述符归一化到“{@code this} 显式前置”的统一形态：
	 * 实例方法的 {@code (X)Y} → {@code (L<owner>;X)Y}，静态方法原样返回。
	 *
	 * <p>与 {@link AnnotationTransformer#forceStaticLambdas} 生成的描述符形态严格一致，
	 * 以便分组（{@link #groupByLogic}）与匹配（{@link #isSignatureCompatible}）使用同一个口径。</p>
	 */
	private static String normalizeStaticShape(String owner, boolean isStatic, String desc) {
		if (isStatic || owner == null || desc == null || desc.isEmpty() || desc.charAt(0) != '(') return desc;
		return "(L" + owner + ";" + desc.substring(1);
	}

	/**
	 * 从方法名中提取逻辑名称，用于分组匹配。
	 *
	 * <p>滑动去除字符串中所有“紧跟在 {@code $} 符号后面的纯数字”：</p>
	 * <pre>
	 * lambda$foo$1$bar$2         -> lambda$foo$$bar$
	 * build$lambda$26$lambda$25  -> build$lambda$$lambda$
	 * $deserializeLambda$        -> $deserializeLambda$
	 * access$100                 -> access$100（不归一化，见下）
	 * </pre>
	 *
	 * <p>{@code access$} 前缀直接返回原名：它是跨类引用，本对齐器对其采用
	 * {@code renameable=false} 的“保名不改名”策略。需要诚实说明的是：
	 * 保名只是不让情况变得更糟，<b>并不能真正解决跨类调用错位</b>
	 * —— javac 的 {@code access$NNN} 编号在两次编译间可能指向不同的成员。
	 * 这是本工具的<b>已知限制</b>；Java 11+ 的 nestmate 场景下该问题基本不存在，
	 * Kotlin/Scala 生成物请自行评估。</p>
	 */
	private static String extractLogicalName(String name) {
		if (name.startsWith("access$")) return name;

		StringBuilder sb    = new StringBuilder(name.length());
		char[]        chars = name.toCharArray();
		int           i     = 0;
		while (i < chars.length) {
			char c = chars[i];
			sb.append(c);
			if (c == '$') {
				int j = i + 1;
				while (j < chars.length && Character.isDigit(chars[j])) j++;
				if (j > i + 1) i = j - 1;
			}
			i++;
		}
		return sb.toString();
	}

	/**
	 * 按逻辑名称和描述符对合成方法进行分组。
	 *
	 * <p>分组键为 {@code compositeHash(logicalName, normalizedDesc)}，保证“嵌套 lambda 序号
	 * 整体位移”不会拆散同一逻辑组。</p>
	 *
	 * <p><b>为什么用归一化描述符</b>：分组是阶段一匹配的前置条件。若直接拿原始描述符做键，
	 * “老版本是静态方法（{@code this} 显式首参数）、新版本是实例方法（{@code this} 隐式）”
	 * 这一对<b>根本不会落进同一个组</b>，{@link #isSignatureCompatible} 再宽容也没有机会执行。
	 * 归一化后两者同键，阶段一即可识别为同一 lambda，避免生成 {@code lambda$build$21}
	 * 这类避障名。<b>注意</b>：分组是“放宽”，真正的取舍仍由
	 * {@link #isSignatureCompatible} 决定：它比对带 owner 的完整参数类型，因此不会因为
	 * 放宽分组而把不同逻辑的 lambda 凑成一对。</p>
	 *
	 * <p>分组键里同时含 {@code logicalName}，所以不同 lambda 体（不同逻辑名）天然隔离；
	 * 描述符里含完整参数类型，所以同名但参数类型不同的重载也不会互相污染。</p>
	 */
	private static void groupByLogic(
		LongObjectMap<List<SyntheticInfo>> target, SyntheticInfo info, String owner) {
		String              normalizedDesc = normalizeStaticShape(owner, info.isStatic(), info.desc);
		long                key            = Utils.compositeHash(info.logicalName, normalizedDesc);
		List<SyntheticInfo> list           = target.get(key);
		if (list == null) {
			list = new ArrayList<>(8);
			target.put(key, list);
		}
		list.add(info);
	}
	//endregion

	//region 数据结构

	/**
	 * 合成方法的信息载体。
	 * <p>只在 {@link MatchContext} 生命周期内有效，{@code align} 结束后不再引用。</p>
	 */
	static class SyntheticInfo {
		/** 方法名。 */
		String  name;
		/** 方法描述符。 */
		String  desc;
		/** 逻辑名称（已归一化），用于分组。 */
		String  logicalName;
		/** 访问修饰符。 */
		int     access;
		/** 指纹哈希值。 */
		long    hash;
		/** 是否已被匹配。 */
		boolean matched;
		/**
		 * 当该方法在所有趟中均未找到任何匹配候选时，被标为 settled。
		 * 其父方法在 {@link #hasUnmatchedChild} 中不再被该子方法阻挡（跳过未匹配且 settled 的子）。
		 */
		boolean settled;
		/** 是否参与重命名（{@code access$} 系列为 false）。 */
		boolean renameable;
		/**
		 * 是否"只用于参与孤儿计算、不参与任何匹配"。
		 * <p>用于上一轮注入的<b>幽灵空壳</b>：它的名字是"死名字"，只能用来兜住老
		 * {@code CallSite}，绝不该再被当成某个新 lambda 的匹配目标。</p>
		 */
		boolean ghost;

		/**
		 * 该方法体里<b>直接引用</b>的本类 lambda 系方法名集合（内层 lambda）。
		 *
		 * <p>用于把匹配排成"由下往上"：父 lambda 必须等子 lambda 落定后再配对，
		 * 否则子被改名会让父的方法体指向别处（详见 {@link #hasUnmatchedChild} 的说明）。</p>
		 */
		List<String> children = Collections.emptyList();

		/** {@link #children} 对应的新类 SyntheticInfo（下标对齐；仅新类侧填充）。 */
		List<SyntheticInfo> childInfos = Collections.emptyList();

		/** 与哪个旧方法配成了一对（未匹配时为 null）。父层据此过滤候选。 */
		SyntheticInfo matchedWith;

		/**
		 * 递归语义指纹：{@link #hash} 再逐层折入每个子 lambda 的语义指纹。
		 *
		 * <p>{@link #hash}（票据指纹）会把子 lambda 的名字屏蔽成 {@code #SYNTHETIC_METHOD#}，
		 * 因此"整棵子树只差最深处叶子"的两个 lambda 会得到相同的 {@code hash} ——
		 * 三层嵌套正是如此：两个中层同 hash、两个外层同 hash，据此**无法区分谁是谁**
		 * （否决与正向选择同时失效）。实测见 scratch/hstest/deep/。</p>
		 *
		 * <p>把子的语义指纹折进来后，差异会沿树<b>向上传播</b>：叶子不同 ⇒ 中层语义指纹不同
		 * ⇒ 外层语义指纹也不同。匹配因此不需要知道深度、也不需要单独的正向选择。</p>
		 *
		 * <p>{@code 0} 表示尚未定稿（子还没算完）。叶子没有子，语义指纹就等于票据指纹。</p>
		 */
		long semanticHash;

		/**
		 * 子树**形状**：只由 indy 引用拓扑决定，与方法体内容无关。
		 *
		 * <pre>
		 *   shape(m) = "(" + 排序后的 shape(子) 串接 + ")"
		 *   叶子 = "()"      中层 = "(())"      外层 = "((()))"
		 * </pre>
		 *
		 * <p>用途（实测问题）：{@link #sameNestingLevel} 早先只比"有无子 + 子数量"，
		 * 于是<b>外层与中层结构等价</b>（都是 1 个子），互换时无人拦截 —— save3 的
		 * 两轮序列里 `$3`（旧外层）与 `$4`（旧中层）真的互换了，持有 `$3` 的老回调
		 * 从此执行中层的逻辑，延迟与嵌套层数都变了却不报错。</p>
		 *
		 * <p>形状与内容无关，所以"编辑叶子方法体"不会改变它（不会重现上一轮那个
		 * "祖先因后代被编辑而失去证据"的问题），但它能区分外层与中层。</p>
		 */
		/**
		 * 子树形状；{@code null} 表示<b>尚未定稿</b>。
		 *
		 * <p><b>为什么用 null 而不是 "()"</b>：{@code "()"} 本身就是叶子的合法形状。
		 * 若用它兼作"未算完"的哨兵，父读到未定稿的子时无法与"真叶子"区分，会算出一个
		 * <b>看似合法实则错误</b>的值 —— 这正是 save3 两轮互换那个 bug 的类别：
		 * 新旧两侧同错，任何等值校验都说不出话。用 null 之后，"未算完"与"叶子"永远可分。</p>
		 */
		String shape;

		/**
		 * 上行深度：该 lambda 被多少层 lambda 嵌套引用。
		 *
		 * <p>{@code build()} 里直接写的 {@code run(() -> x)} 深度为 <b>0</b>；
		 * 若它又被另一层 lambda 包着，则深度为 1，依此类推。</p>
		 *
		 * <p><b>哨兵必须用 -1（未定稿），不能用 0</b> —— 0 是合法值，用它兼作哨兵会重演
		 * {@link #shape} 那个坑：父读到未定稿的值却当成合法值用了。</p>
		 *
		 * <p>用途：两个都没有指纹证据的新叶子争抢同一个旧叶子名时（实测见
		 * {@code scratch/hstest/UpDepthTest}），"被谁引用"是唯一可用的结构信号 ——
		 * 一个由 {@code build()} 直接引用（深度 0），另一个被嵌套链引用（深度 2）。
		 * 它是纯结构信息，编辑方法体不会改变它，与 {@link #shape} 对称。</p>
		 */
		int upDepth = -1;

		SyntheticInfo(String name, String desc, int access, long hash, String logicalName, boolean renameable) {
			this.name        = name;
			this.desc        = desc;
			this.access      = access;
			this.hash        = hash;
			this.logicalName = logicalName;
			this.renameable  = renameable;
			this.matched     = false;
			this.settled     = false;
			this.ghost       = false;
		}

		boolean isStatic() {
			return (access & Opcodes.ACC_STATIC) != 0;
		}
	}
	//endregion
}