package nipx;

import nipx.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

import java.util.*;

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
							&& ni.hash == oi.hash
							&& oi.isStatic() == ni.isStatic()
							&& oi.desc.equals(ni.desc)
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
							&& ni.hash == oi.hash
							&& oi.isStatic() == ni.isStatic()
							&& oi.desc.equals(ni.desc)) {
							recordRename(ctx, ni, oi.name);
							ni.matched = true;
							oi.matched = true;
							ctx.usedOldNames.add(oi.name);
							break;
						}
					}
				}

				// Step 2：顺序对齐（static 与 desc 必须一致）
				for (SyntheticInfo ni : newGroup) {
					if (ni.matched || !ni.renameable) continue;

					SyntheticInfo bestOld = null;

					// 第一优先级：组内同名且 static / desc 一致
					for (SyntheticInfo oi : oldGroup) {
						if (oi.matched) continue;
						if (oi.isStatic() != ni.isStatic()) continue;
						if (!oi.desc.equals(ni.desc)) continue;
						if (oi.name.equals(ni.name)) { bestOld = oi; break; }
					}

					// 第二优先级：第一个 static / desc 一致的未匹配旧方法
					if (bestOld == null) {
						for (SyntheticInfo oi : oldGroup) {
							if (oi.matched) continue;
							if (oi.isStatic() != ni.isStatic()) continue;
							if (!oi.desc.equals(ni.desc)) continue;
							bestOld = oi;
							break;
						}
					}

					if (bestOld != null) {
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
			groupByLogic(isOld ? ctx.oldGroups : ctx.newGroups, info);
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
		ClassWriter        cw     = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
		final OrphanPolicy policy = orphanPolicy;

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
						injectDummyBody(dummy, mn.name, mn.desc, policy);
					}
				}
				super.visitEnd();
			}
		};
		cr.accept(cv, 0);
		return cw.toByteArray();
	}

	/**
	 * 按给定策略生成空方法体的指令流。
	 *
	 * <p>返回值按 {@link Type#getSort()} 分派，统一返回类型默认值（0 / null / false）
	 * 或抛出异常。栈大小交给 {@code COMPUTE_MAXS} 处理。</p>
	 *
	 * <p><b>注意</b>：{@link OrphanPolicy#LOG_AND_RETURN_DEFAULT} 每次调用都会打日志，
	 * 若孤儿 lambda 位于热路径上可能刷屏。生产环境建议切到 {@link OrphanPolicy#THROW}。</p>
	 */
	private static void injectDummyBody(MethodVisitor mv, String name, String desc, OrphanPolicy policy) {
		mv.visitCode();
		Type returnType = Type.getReturnType(desc);

		if (policy == OrphanPolicy.SMART_ADAPTIVE) {
			Label returnDefault = new Label();
			// 1. 调用 LambdaAligner.isCalledByUpdateRef()
			mv.visitMethodInsn(Opcodes.INVOKESTATIC,
				Type.getInternalName(LambdaAligner.class),
				"isCalledByUpdateRef", "()Z", false);
			// 若非 UpdateRef 保护调用，跳转返回默认值
			mv.visitJumpInsn(Opcodes.IFEQ, returnDefault);

			// 2. 是 UpdateRef 发起的调用：抛出 NoSuchMethodError 触发局部熔断与清理
			mv.visitTypeInsn(Opcodes.NEW, "java/lang/NoSuchMethodError");
			mv.visitInsn(Opcodes.DUP);
			mv.visitLdcInsn("Lambda removed by hot swap: " + name + desc);
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
				"java/lang/NoSuchMethodError", "<init>", "(Ljava/lang/String;)V", false);
			mv.visitInsn(Opcodes.ATHROW);

			// 3. 普通业务调用：打印警告日志并返回类型默认值，绝不崩溃
			mv.visitLabel(returnDefault);
			mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
			mv.visitLdcInsn("[LambdaAligner] orphaned lambda invoked: " + name + desc);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
				"java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

			injectDefaultReturnValue(mv, returnType, desc);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
			return;
		}

		if (policy == OrphanPolicy.THROW_NO_SUCH_METHOD) {
			mv.visitTypeInsn(Opcodes.NEW, "java/lang/NoSuchMethodError");
			mv.visitInsn(Opcodes.DUP);
			mv.visitLdcInsn("Lambda removed by hot swap: " + name + desc);
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
				"java/lang/NoSuchMethodError", "<init>", "(Ljava/lang/String;)V", false);
			mv.visitInsn(Opcodes.ATHROW);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
			return;
		}

		if (policy == OrphanPolicy.THROW) {
			mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
			mv.visitInsn(Opcodes.DUP);
			mv.visitLdcInsn("Lambda removed by hot swap: " + name + desc);
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
				"java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
			mv.visitInsn(Opcodes.ATHROW);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
			return;
		}

		if (policy == OrphanPolicy.LOG_AND_RETURN_DEFAULT) {
			mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
			mv.visitLdcInsn("[LambdaAligner] orphaned lambda invoked: " + name + desc);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
				"java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
		}

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

	private static final String UPDATE_REF_CLASS_NAME = "nipx.ref.UpdateRef";

	/**
	 * 检查当前调用栈浅层中是否存在 {@link nipx.ref.UpdateRef}。
	 * <p>
	 * 优先使用 Java 9+ 的 {@link StackWalker} 进行高效的浅层流式遍历（limit(8) 即刻短路）；
	 * 若当前运行环境缺少 StackWalker（如 Android Dalvik/ART 或 Java 8），则安全降级为 {@link Throwable#getStackTrace()}，
	 * 保证在全平台环境下均能 100% 稳定运行。
	 * </p>
	 *
	 * @return 若调用栈来自 UpdateRef 则返回 true，否则返回 false
	 */
	public static boolean isCalledByUpdateRef() {
		try {
			if (StackWalkerHolder.IS_SUPPORTED) {
				return StackWalkerHolder.WALKER.walk(s -> s.limit(8)
					.anyMatch(f -> UPDATE_REF_CLASS_NAME.equals(f.getClassName())));
			}
		} catch (Throwable ignored) {}

		try {
			StackTraceElement[] trace = new Throwable().getStackTrace();
			int limit = Math.min(trace.length, 8);
			for (int i = 1; i < limit; i++) {
				if (UPDATE_REF_CLASS_NAME.equals(trace[i].getClassName())) {
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
	 * <p>分组键为 {@code compositeHash(logicalName, desc)}，保证“嵌套 lambda 序号
	 * 整体位移”不会拆散同一逻辑组。</p>
	 */
	private static void groupByLogic(
		LongObjectMap<List<SyntheticInfo>> target, SyntheticInfo info) {
		long                key  = Utils.compositeHash(info.logicalName, info.desc);
		List<SyntheticInfo> list = target.get(key);
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

		SyntheticInfo(String name, String desc, int access, long hash, String logicalName, boolean renameable) {
			this.name        = name;
			this.desc        = desc;
			this.access      = access;
			this.hash        = hash;
			this.logicalName = logicalName;
			this.renameable  = renameable;
			this.matched     = false;
		}

		boolean isStatic() {
			return (access & Opcodes.ACC_STATIC) != 0;
		}
	}
	//endregion
}