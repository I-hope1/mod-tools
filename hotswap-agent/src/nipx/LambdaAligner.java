package nipx;

import nipx.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
 * @see nipx.ref.UpdateRef
 * @see nipx.LambdaRef
 * @see nipx.HotSwapAgent
 */
public class LambdaAligner {

	public static final ThreadLocal<MatchContext> CONTEXT = ThreadLocal.withInitial(MatchContext::new);

	/**
	 * 孤儿 lambda 被复活为空壳时的处理策略。
	 */
	public enum OrphanPolicy {
		/**
		 * 智能自适应策略（<b>推荐且默认</b>）：
		 * <ul>
		 *   <li>若由 {@link nipx.ref.UpdateRef} 发起（UI / 定时轮询 / 事件回调）：抛出 {@link NoSuchMethodError}，
		 *       触发精准局部熔断（如 {@code element.update(null)}），彻底切断 60FPS 空转与死循环刷屏；</li>
		 *   <li>若由普通业务代码发起（未受 UpdateRef 保护）：向 {@code System.err} 打印警告并返回默认值（0 / null / false），
		 *       绝不抛出异常引发程序崩溃。</li>
		 * </ul>
		 */
		SMART_ADAPTIVE,
		/** 向 {@code System.err} 打印一条日志，并返回默认值（0 / null / false）。 */
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

			// 【阶段一】抢占 / 复用旧名
			for (int idx = newGroups.nextEntry(-1); idx != -1; idx = newGroups.nextEntry(idx)) {
				List<SyntheticInfo> newGroup = newGroups.valueAt(idx);
				if (newGroup == null) continue;

				List<SyntheticInfo> oldGroup = oldGroups.get(newGroups.keyAt(idx));
				if (oldGroup == null) continue;

				int newSize = newGroup.size();
				int oldSize = oldGroup.size();

				// Step 1a：hash 相同 && 同名 —— 首选，保持原名，避免“同指纹交叉夺舍”
				for (int j = 0; j < newSize; j++) {
					SyntheticInfo ni = newGroup.get(j);
					if (!ni.renameable || ni.matched) continue;
					for (int k = 0; k < oldSize; k++) {
						SyntheticInfo oi = oldGroup.get(k);
						if (!oi.matched
							&& !oi.ghost
							&& ni.hash == oi.hash
							&& isSignatureCompatible(ctx.currentClass, oi, ni)
							&& oi.name.equals(ni.name)) {
							recordRename(ctx, ni, oi.name);
							ni.matched = true;
							oi.matched = true;
							ctx.usedOldNames.add(oi.name);
							break;
						}
					}
				}

				// Step 1b：hash 相同（不限名字）—— 次选
				for (int j = 0; j < newSize; j++) {
					SyntheticInfo ni = newGroup.get(j);
					if (!ni.renameable || ni.matched) continue;
					for (int k = 0; k < oldSize; k++) {
						SyntheticInfo oi = oldGroup.get(k);
						if (!oi.matched
							&& !oi.ghost
							&& ni.hash == oi.hash
							&& isSignatureCompatible(ctx.currentClass, oi, ni)) {
							recordRename(ctx, ni, oi.name);
							ni.matched = true;
							oi.matched = true;
							ctx.usedOldNames.add(oi.name);
							break;
						}
					}
				}
			}

			// 【阶段一·中】全类跨组指纹匹配 —— 必须夹在 Step 1 与 Step 2 之间
			//
			// 顺序不是风格问题，而是正确性要求。反例（已实测，见 scratch/hstest/move/）：
			//   v1: build1(){ r1 = () -> A }   build2(){ r2 = () -> Z }
			//   v2: build1(){}                 build2(){ r2 = () -> A }   // A 换了承载方法，Z 被删
			// 新类里 A 变成 lambda$build2$0。若先跑 Step 2 的顺序回退，它会以"同组同位置"的身份
			// 配上旧 lambda$build2$0，把 Z 的名字占掉：
			//   • 持有旧 Z 的 CallSite 悄悄去跑 A 的方法体（不抛异常，最难查）；
			//   • 持有旧 A 的 CallSite 反而命中幽灵空壳。
			// 把跨组指纹匹配提前，A 会先按指纹认回它自己的旧名 lambda$build1$0，
			// Z 则干净地变成孤儿空壳 —— 旧 CallSite 各自回到正确归宿。
			matchByFingerprintAcrossGroups(ctx);

			// 【阶段一·下】Step 2：顺序回退 —— 只处理"指纹也对不上、仍无归宿"的新方法
			for (int idx = newGroups.nextEntry(-1); idx != -1; idx = newGroups.nextEntry(idx)) {
				List<SyntheticInfo> newGroup = newGroups.valueAt(idx);
				if (newGroup == null) continue;

				List<SyntheticInfo> oldGroup = oldGroups.get(newGroups.keyAt(idx));
				if (oldGroup == null) continue;

				// Step 2：顺序对齐（签名逻辑等价即可：实例方法的隐式 this 与静态方法的显式 this 等价）
				for (SyntheticInfo ni : newGroup) {
					if (ni.matched || !ni.renameable) continue;

					SyntheticInfo bestOld = null;

					// 第一优先级：组内同名且签名逻辑等价
					for (SyntheticInfo oi : oldGroup) {
						if (oi.matched || oi.ghost) continue;
						if (!isSignatureCompatible(ctx.currentClass, oi, ni)) continue;
						if (oi.name.equals(ni.name)) { bestOld = oi; break; }
					}

					// 第二优先级：第一个签名逻辑等价的未匹配旧方法
					if (bestOld == null) {
						for (SyntheticInfo oi : oldGroup) {
							if (oi.matched || oi.ghost) continue;
							if (!isSignatureCompatible(ctx.currentClass, oi, ni)) continue;
							bestOld = oi;
							break;
						}
					}

					if (bestOld != null) {
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
						ni.matched = true;
						bestOld.matched = true;
						ctx.usedOldNames.add(bestOld.name);
					}
				}
			}

			// 【阶段二】未匹配的新方法统一处理
			int freshId = 0;
			for (int idx = newGroups.nextEntry(-1); idx != -1; idx = newGroups.nextEntry(idx)) {
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

			// 传入 oldCn（已解析过一次）—— 避免 resurrectOrphanedLambdas 二次读 oldBytes
			return resurrectOrphanedLambdas(oldCn, alignedBytes, presentKeys, ctx);
		} catch (Exception e) {
			// 降级：不崩溃，返回原始字节码
			HotSwapAgent.info("[LambdaAligner] align failed, fallback to newBytes: " + e);
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
	private static void matchByFingerprintAcrossGroups(MatchContext ctx) {
		var newGroups = ctx.newGroups;
		var oldGroups = ctx.oldGroups;

		for (int idx = newGroups.nextEntry(-1); idx != -1; idx = newGroups.nextEntry(idx)) {
			List<SyntheticInfo> newGroup = newGroups.valueAt(idx);
			if (newGroup == null) continue;

			for (SyntheticInfo ni : newGroup) {
				if (ni.matched || !ni.renameable) continue;

				SyntheticInfo bestOld = null;

				// 第一优先级：同组内指纹相同（保持 Step 1 之外的原配对倾向）
				List<SyntheticInfo> sameGroup = oldGroups.get(newGroups.keyAt(idx));
				if (sameGroup != null) {
					bestOld = firstFingerprintMatch(sameGroup, ni, ctx.currentClass, true);
				}
				// 第二优先级：全类范围内指纹相同
				if (bestOld == null) {
					outer:
					for (int k = oldGroups.nextEntry(-1); k != -1; k = oldGroups.nextEntry(k)) {
						List<SyntheticInfo> g = oldGroups.valueAt(k);
						if (g == null || g == sameGroup) continue;
						SyntheticInfo oi = firstFingerprintMatch(g, ni, ctx.currentClass, true);
						if (oi != null) { bestOld = oi; break outer; }
					}
				}
				if (bestOld == null) continue;

				recordRename(ctx, ni, bestOld.name);
				ni.matched = true;
				bestOld.matched = true;
				ctx.usedOldNames.add(bestOld.name);
			}
		}
	}

	/**
	 * 在 {@code group} 中找第一个与 {@code ni} 指纹相同且未被匹配的旧方法。
	 *
	 * @param preferSameName 是否优先命中与 {@code ni} 同名的候选（同名意味着
	 *                       "编译期序号都没变"，是更强的证据）
	 * @return 命中的旧方法信息；没有则返回 {@code null}
	 */
	private static SyntheticInfo firstFingerprintMatch(List<SyntheticInfo> group, SyntheticInfo ni,
	                                                   String owner, boolean preferSameName) {
		if (group == null) return null;

		if (preferSameName) {
			for (SyntheticInfo oi : group) {
				if (oi.matched || oi.ghost || oi.hash != ni.hash) continue;
				if (!isSignatureCompatible(owner, oi, ni)) continue;
				if (oi.name.equals(ni.name)) return oi;
			}
		}
		for (SyntheticInfo oi : group) {
			if (oi.matched || oi.ghost || oi.hash != ni.hash) continue;
			if (!isSignatureCompatible(owner, oi, ni)) continue;
			return oi;
		}
		return null;
	}

	/** 顺序回退告警去重缓存：同一处错配只报一次。 */
	private static final Set<String> WARNED_REALIGNMENTS =
		Collections.newSetFromMap(new ConcurrentHashMap<>());

	/** 清空顺序回退告警去重缓存（与 {@link #clearLoggedOrphans()} 同步调用）。 */
	public static void clearLoggedWarnings() {
		WARNED_REALIGNMENTS.clear();
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
			+ "老 CallSite 可能去执行另一个回调的方法体。"
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
			// access$ 是跨类引用，本对齐器只做“保名不改名”
			boolean renameable  = !mn.name.startsWith("access$");
			SyntheticInfo info  = new SyntheticInfo(
				mn.name, mn.desc, mn.access, fp.getHash(), logicalName, renameable);
			// 幽灵空壳（上一轮为兜住老 CallSite 而注入的空方法）标记为"不参与匹配"：
			// 它必须留在 oldGroups 里供孤儿计算复现，但名字已经是"死名字"，
			// 不能再被当成某个新 lambda 的目标 —— 否则会把它挤到别的名字上去。
			info.ghost = isGhostMethod(mn);
			groupByLogic(isOld ? ctx.oldGroups : ctx.newGroups, info, cn.name);
		}

		return cn;
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
		List<MethodNode> toInject = new ArrayList<>();
		for (MethodNode mn : oldCn.methods) {
			if (orphanedKeys.contains(mn.name + mn.desc)) toInject.add(mn);
		}

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
	 * {@link #onOrphanInvoked(String, String, String)} 中执行。<br>
	 * <ul>
	 *   <li>若为 UpdateRef 保护的调用：Java 静态方法直接抛出 {@link NoSuchMethodError} 展开栈中断执行；</li>
	 *   <li>若为普通业务调用：Java 静态方法去重打印警告日志后正常返回，随后由生成的字节码 100% 线性返回类型默认值（0 / null / false）。</li>
	 * </ul>
	 * 从而在 Java 7+ / 8 / 11 / 17 / 21 任意平台环境下，无需 StackMapTable 即可 100% 通过 JVM 类验证。
	 * </p>
	 */
	private static void injectDummyBody(MethodVisitor mv, String className, String name, String desc) {
		mv.visitCode();
		Type returnType = Type.getReturnType(desc);

		// 1. 调用纯 Java 静态方法：统一处理策略分发、UpdateRef 栈探测（若命中抛出 NoSuchMethodError）及日志去重
		mv.visitLdcInsn(className != null ? className : "");
		mv.visitLdcInsn(name);
		mv.visitLdcInsn(desc);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC,
			Type.getInternalName(LambdaAligner.class),
			"onOrphanInvoked", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false);

		// 2. 100% 线性无分支返回类型默认值（若上面抛出异常则直接展开调用栈，根本不会执行到此）
		injectDefaultReturnValue(mv, returnType, desc);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
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

	private static final String UPDATE_REF_CLASS_PREFIX = "nipx.ref.UpdateRef";
	private static final Set<String> LOGGED_ORPHANS = Collections.newSetFromMap(new ConcurrentHashMap<>());

	/**
	 * 当热重载中被删除/孤立的空壳 Lambda 方法被调用时由生成的字节码调用。
	 * <p>
	 * 所有的策略判定、UpdateRef 栈探测、异常抛出与日志去重均下沉到此 Java 方法中执行，
	 * 从而保证 ASM 生成的字节码 100% 线性无跳转分支，彻底杜绝 Java 7+ 下因缺少 StackMapTable 引发的 {@link VerifyError}。
	 * </p>
	 *
	 * @param className 调用方类名（内部形式，形如 {@code com/example/Foo}）
	 * @param name      方法名
	 * @param desc      方法描述符
	 */
	public static void onOrphanInvoked(String className, String name, String desc) {
		OrphanPolicy policy = orphanPolicy;
		String location = (className != null && !className.isEmpty() ? className.replace('/', '.') + "#" : "") + name + desc;

		if (policy == OrphanPolicy.SMART_ADAPTIVE) {
			if (isCalledByUpdateRef()) {
				throw new NoSuchMethodError("Lambda removed by hot swap: " + location);
			}
			// 普通业务调用：去重后打印日志，随后正常返回，由外部字节码线性返回默认值
			if (LOGGED_ORPHANS.add(location)) {
				System.err.println("[LambdaAligner] orphaned lambda invoked: " + location + " (subsequent invocations will be muted)");
			}
			return;
		}

		if (policy == OrphanPolicy.THROW_NO_SUCH_METHOD) {
			throw new NoSuchMethodError("Lambda removed by hot swap: " + location);
		}

		if (policy == OrphanPolicy.THROW) {
			throw new IllegalStateException("Lambda removed by hot swap: " + location);
		}

		if (policy == OrphanPolicy.LOG_AND_RETURN_DEFAULT) {
			if (LOGGED_ORPHANS.add(location)) {
				System.err.println("[LambdaAligner] orphaned lambda invoked: " + location + " (subsequent invocations will be muted)");
			}
			return;
		}

		// OrphanPolicy.SILENT: 静默返回
	}

	/**
	 * 检查当前调用栈浅层中是否存在 {@link nipx.ref.UpdateRef}。
	 * <p>
	 * 优先使用 Java 9+ 的 {@link StackWalker} 进行高效的浅层流式遍历（limit(16) 即刻短路）；
	 * 若当前运行环境缺少 StackWalker（如 Android Dalvik/ART 或 Java 8），则安全降级为 {@link Throwable#getStackTrace()}，
	 * 保证在全平台环境下均能 100% 稳定运行。
	 * </p>
	 *
	 * @return 若调用栈来自 UpdateRef 则返回 true，否则返回 false
	 */
	public static boolean isCalledByUpdateRef() {
		try {
			if (StackWalkerHolder.IS_SUPPORTED) {
				return StackWalkerHolder.WALKER.walk(s -> s.limit(16)
					.anyMatch(f -> f.getClassName().startsWith(UPDATE_REF_CLASS_PREFIX)));
			}
		} catch (Throwable ignored) {}

		try {
			StackTraceElement[] trace = new Throwable().getStackTrace();
			int limit = Math.min(trace.length, 16);
			for (int i = 1; i < limit; i++) {
				if (trace[i].getClassName().startsWith(UPDATE_REF_CLASS_PREFIX)) {
					return true;
				}
			}
		} catch (Throwable ignored) {}
		return false;
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
				walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
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
		/** 是否参与重命名（{@code access$} 系列为 false）。 */
		boolean renameable;
		/**
		 * 是否"只用于参与孤儿计算、不参与任何匹配"。
		 * <p>用于上一轮注入的<b>幽灵空壳</b>：它的名字是"死名字"，只能用来兜住老
		 * {@code CallSite}，绝不该再被当成某个新 lambda 的匹配目标。</p>
		 */
		boolean ghost;

		SyntheticInfo(String name, String desc, int access, long hash, String logicalName, boolean renameable) {
			this.name        = name;
			this.desc        = desc;
			this.access      = access;
			this.hash        = hash;
			this.logicalName = logicalName;
			this.renameable  = renameable;
			this.matched     = false;
			this.ghost       = false;
		}

		boolean isStatic() {
			return (access & Opcodes.ACC_STATIC) != 0;
		}
	}
	//endregion
}