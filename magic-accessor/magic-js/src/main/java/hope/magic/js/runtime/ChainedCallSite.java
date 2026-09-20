package hope.magic.js.runtime;

import hope.magic.js.runtime.JSLinker.PolySnapshot;

import java.lang.invoke.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

public class ChainedCallSite extends MutableCallSite {
	public static final int          MAX_CHAIN_DEPTH = 5; // Shape 种类 <= 5 时使用链式 Guard (覆盖常见 4~5 形态多态), > 5 时自动演化为 Megamorphic 缓存表
	private             int          chainDepth      = 0;
	private volatile    boolean      megamorphic     = false;
	private             MethodHandle megamorphicTarget;
	private final List<WeakReference<Class<?>>> recordedClasses = new ArrayList<>(4);

	private       int       polyCount       = 0;
	private final JSShape[] recordedShapes  = new JSShape[MAX_CHAIN_DEPTH];
	private final long[]    recordedEntries = new long[MAX_CHAIN_DEPTH];
	public static long packCacheEntry(int shapeId, byte type, int offset) {
		return ((long) shapeId << 32) | (((long) type & 0xFF) << 24) | (offset & 0xFFFFFFL);
	}
	public static int unpackShapeId(long entry) { return (int) (entry >>> 32); }
	public static byte unpackType(long entry) { return (byte) ((entry >>> 24) & 0xFF); }
	public static int unpackOffset(long entry) { return (int) (entry & 0xFFFFFFL); }

	// Offset-Equivalent IC (同偏移多态状态)
	private volatile int     commonOffset     = -1;
	private volatile byte    commonType       = -1;
	private volatile boolean offsetEquivalent = true;
	private volatile int     propId           = -1;

	public void setPropId(int propId) {
		this.propId = propId;
	}

	public int getPropId() {
		return propId;
	}

	// Megamorphic 多槽直接映射表 (Direct Mapped Fast Shape->Offset Cache)
	public static final int CACHE_SIZE  = Integer.getInteger("magic.cache.size", 512);
	public static final int CACHE_SHIFT = 32 - Integer.numberOfTrailingZeros(CACHE_SIZE);
	public static final int PHI_32      = 0x9E3779B9; // 黄金比例常数

	public static final boolean ENABLE_STATS = Boolean.getBoolean("magic.cache.stats");
	public static final LongAdder STATS_HITS = new LongAdder();
	public static final LongAdder STATS_MISSES = new LongAdder();

	/** 极速 32 位黄金比例散列：单条 imul + 单条 shr 汇编指令 */
	public static int cacheIndex(int shapeId) {
		return (shapeId * PHI_32) >>> CACHE_SHIFT;
	}

	// directCache (惰性分配，避免海量单态/小多态 CallSite 空占数组堆内存)
	public static final VarHandle CACHE_VH = MethodHandles.arrayElementVarHandle(long[].class);
	public volatile     long[]    directCache;

	public long[] getOrCreateDirectCache() {
		long[] cache = directCache;
		if (cache == null) {
			synchronized (this) {
				cache = directCache;
				if (cache == null) {
					cache = new long[CACHE_SIZE];
					directCache = cache;
				}
			}
		}
		return cache;
	}


	public ChainedCallSite(MethodType type, MethodHandle megamorphicTarget) {
		super(type);
		this.megamorphicTarget = megamorphicTarget;
	}

	public void setMegamorphicTarget(MethodHandle target) {
		this.megamorphicTarget = target;
	}

	public MethodHandle getMegamorphicTarget() {
		return this.megamorphicTarget;
	}

	private MethodHandle initialFallback;

	public void setInitialFallback(MethodHandle initialFallback) {
		this.initialFallback = initialFallback;
	}

	public MethodHandle getInitialFallback() {
		return initialFallback;
	}

	public boolean isMegamorphic() {
		return megamorphic;
	}

	public int getChainDepth() {
		return chainDepth;
	}

	public synchronized void recordShape(JSShape shape, int offset, byte type) {
    int shapeId = shape.id;

    // 检查是否已经记录过该 shape
    for (int i = 0; i < polyCount; i++) {
        if (unpackShapeId(recordedEntries[i]) == shapeId) {
            return; // 已经存在，不再重复处理
        }
    }

    // 维护 commonOffset 与 offsetEquivalent 状态
    if (commonOffset == -1) {
        commonOffset = offset;
        commonType = type;
    } else if (commonOffset != offset || commonType != type) {
        // 只有在【真正发现 offset 不同 或 type 不同】时，才判定为不等价！
        offsetEquivalent = false;
    }

		// 记录到数组（仅保留前 MAX_CHAIN_DEPTH 个用于生成特化 GWT / TableSwitch）
		if (polyCount < MAX_CHAIN_DEPTH) {
			recordedShapes[polyCount] = shape;
			recordedEntries[polyCount] = packCacheEntry(shapeId, type, offset);
			polyCount++;
			return;
		}

		// 超过 MAX_CHAIN_DEPTH 且非同偏移等价，立即进化为 Megamorphic
		if (!offsetEquivalent) {
			megamorphic = true;
			getOrCreateDirectCache();
			if (megamorphicTarget != null) {
				setTarget(megamorphicTarget.asType(type()));
			}
		}
	}

	public boolean isOffsetEquivalent() {
		return offsetEquivalent && commonOffset >= 0 && polyCount >= 2;
	}

	public int getCommonOffset() {
		return commonOffset;
	}

	public byte getCommonType() {
		return commonType;
	}

	/** 返回已观测到的 Shape 数组（保持插入顺序）。 */
	public synchronized JSShape[] getRecordedShapesArray() {
		return Arrays.copyOf(recordedShapes, polyCount);
	}
	public synchronized int getPolyCount() {
		return polyCount;
	}

	/** 快照当前所有 (JSShape, offset) 对（插入顺序），用于构建 tableSwitch。 */
	public synchronized PolySnapshot snapshotPoly() {
		int       n       = polyCount;
		JSShape[] shapes  = new JSShape[n];
		int[]     offsets = new int[n];
		byte[] types = new byte[n];

		for (int i = 0; i < n; i++) {
			shapes[i] = recordedShapes[i];
			offsets[i] = unpackOffset(recordedEntries[i]);
			types[i] = unpackType(recordedEntries[i]);
		}
		return new PolySnapshot(shapes, offsets, types, propId);
	}

	public synchronized boolean installGuardOrSwitchMegamorphic(MethodHandle test, MethodHandle fastTarget) {
		if (megamorphic) return false;
		chainDepth++;
		if (chainDepth > MAX_CHAIN_DEPTH) {
			megamorphic = true;
			getOrCreateDirectCache();
			if (megamorphicTarget != null) setTarget(megamorphicTarget.asType(type()));
			return false;
		}
		MethodHandle guard = MethodHandles.guardWithTest(test, fastTarget.asType(type()), getTarget());
		setTarget(guard);
		return true;
	}

	private boolean hasRecordedClass(Class<?> clazz) {
		for (WeakReference<Class<?>> recordedClass : recordedClasses) {
			Class<?> c = recordedClass.get();
			if (c == clazz) return true;
		}
		return false;
	}

	/**
	 * 已挂载的原型 SwitchPoint 列表（非按 shape 去重）。
	 * 只有当某个 SP 真正失效时才触发 reset()，而非"同一个 shape 再次出现"。
	 *
	 * <p><b>旧实现的问题：</b>用 {@code recordedProtoShapes.contains(shape)} 来判断
	 * "SwitchPoint 已失效，需要重置"。但当多个不同类的实例共享同一个 JSShape（如 ROOT
	 * shape 下的 Dog/Cat），每次交替调用都会命中 contains → 触发 reset()，形成
	 * install→reset→install 死循环，导致 JIT 无法稳定内联，runPoly 退化为 100s 量级。
	 *
	 * <p><b>正确判断：</b>检查已记录的 SwitchPoint 是否确实 {@link SwitchPoint#hasBeenInvalidated()}。
	 * 若否（SP 仍然有效），则直接追加新 guard，不做 reset。
	 * 若是（某个 SP 已失效），才触发 reset() 重建链，同时消耗一次 relink 预算，
	 * 超出 {@link #MAX_RELINKS} 后强制降级为 Megamorphic。
	 */
	private final List<SwitchPoint> recordedProtoSps = new ArrayList<>(4);

	/** 已发生的重链次数（不随 reset() 清零，用于限制振荡场景下的无限重链）。 */
	private int relinkCount = 0;

	/** 最大允许重链次数：超出后强制降级 Megamorphic，避免 runPoly 类场景无限循环。 */
	private static final int MAX_RELINKS = 8;

	public synchronized boolean installProtoGuard(SwitchPoint sp, MethodHandle test, MethodHandle fastTarget) {
		// 单层原型访问（无中间链），allSps 仅含 holderSp
		List<SwitchPoint> allSps = (sp != null) ? Collections.singletonList(sp) : Collections.emptyList();
		return installProtoGuard(sp, allSps, test, fastTarget);
	}

	/**
	 * 带完整 SP 列表的原型守卫安装（深链路径使用）。
	 *
	 * @param allSps 链条中的全部 SwitchPoint（中间层 SP + holder SP），
	 *               用于检测任意一级失效。只有当 {@code allSps} 中某个 SP 确实
	 *               {@link SwitchPoint#hasBeenInvalidated()} 才触发 reset()。
	 */
	public synchronized boolean installProtoGuard(SwitchPoint holderSp,
	                                              List<SwitchPoint> allSps,
	                                              MethodHandle test, MethodHandle fastTarget) {
		// 先检查 megamorphic，避免 reset() 悄悄撤销已降级状态
		if (megamorphic) return false;

		// 扫描已挂载的 SP：只有真正失效的 SP 才需要重建链
		boolean anyInvalidated = false;
		for (SwitchPoint recordedProtoSp : recordedProtoSps) {
			if (recordedProtoSp.hasBeenInvalidated()) {
				anyInvalidated = true;
				break;
			}
		}
		if (anyInvalidated) {
			if (++relinkCount > MAX_RELINKS) {
				// 反复失效 → 场景不稳定，降级为 Megamorphic，不再重建
				megamorphic = true;
				getOrCreateDirectCache();
				if (megamorphicTarget != null) setTarget(megamorphicTarget.asType(type()));
				return false;
			}
			reset(); // 清空链（内部会清 recordedProtoSps）
		}

		// 追加本次链条中的全部 SP（允许 reset 后重新添加）
		recordedProtoSps.addAll(allSps);

		// JS 原型方法 IC 不需要 globalSwitchPoint（globalSp 仅供 Java 类重载场景使用）
		return installGuardWithSwitchPoint(test, holderSp, fastTarget, /*useGlobalSp=*/false);
	}

	public synchronized boolean installJavaGuard(Class<?> clazz, MethodHandle test, MethodHandle fastTarget) {
		if (megamorphic) return false; // 先检查，避免 reset() 悄悄撤销已降级状态
		if (clazz != null && hasRecordedClass(clazz)) {
			// Java class 守卫：同一个类再次进入 fallback 说明其 classSp 或 globalSp 已失效，
			// 链条已断裂，需要重置重建。（与 installProtoGuard 不同，Java class 不存在
			// 多个不相关类共享同一 JSShape 的问题，所以重录即等价于 SP 失效。）
			reset();
		}
		if (clazz != null && !hasRecordedClass(clazz)) {
			recordedClasses.add(new WeakReference<>(clazz));
		}
		SwitchPoint classSp = (clazz != null) ? MagicJIT.getSwitchPoint(clazz) : null;
		return installGuardWithSwitchPoint(test, classSp, fastTarget);
	}

	public synchronized boolean installGuardWithSwitchPoint(MethodHandle test, SwitchPoint switchPoint, MethodHandle fastTarget) {
		return installGuardWithSwitchPoint(test, switchPoint, fastTarget, true);
	}

	/**
	 * @param useGlobalSp if false, skip the globalSwitchPoint wrapper (preferred for JS prototype
	 *                    method ICs where globalSp is irrelevant, saving one MH layer per call).
	 */
	public synchronized boolean installGuardWithSwitchPoint(MethodHandle test, SwitchPoint switchPoint, MethodHandle fastTarget, boolean useGlobalSp) {
		if (megamorphic) return false;
		chainDepth++;
		if (chainDepth > MAX_CHAIN_DEPTH) {
			megamorphic = true;
			getOrCreateDirectCache();
			if (megamorphicTarget != null) setTarget(megamorphicTarget.asType(type()));
			return false;
		}
		MethodHandle guardedTarget = fastTarget.asType(type());
		MethodHandle fb = initialFallback;
		if (fb != null) {
			MethodHandle fbTyped = fb.asType(type());
			if (switchPoint != null) {
				guardedTarget = switchPoint.guardWithTest(guardedTarget, fbTyped);
			}
			if (useGlobalSp) {
				SwitchPoint globalSp = MagicJIT.getGlobalSwitchPoint();
				if (globalSp != null) {
					guardedTarget = globalSp.guardWithTest(guardedTarget, fbTyped);
				}
			}
		}
		MethodHandle guard = MethodHandles.guardWithTest(test, guardedTarget, getTarget());
		setTarget(guard);
		return true;
	}

	public synchronized void reset() {
		this.chainDepth = 0;
		this.megamorphic = false;
		this.polyCount = 0;
		this.commonOffset = -1;
		this.commonType = -1;
		this.offsetEquivalent = true;
		this.recordedClasses.clear();
		this.recordedProtoSps.clear();  // 清空 SP 列表，relinkCount 故意保留（跨 reset 限制总重链次数）
		Arrays.fill(recordedShapes, null);
		Arrays.fill(recordedEntries, 0L);
		if (initialFallback != null) {
			setTarget(initialFallback.asType(type()));
		}
	}

	/**
	 * 挂载扁平多态 Jump-Table 守卫（单层 switch，彻底消除 N 层 LambdaForm 嵌套）。
	 * 仅在 chainDepth >= 2 && !offsetEquivalent 时由 Fallback 方法调用。
	 */
	public synchronized void installFlatPolyGuard(MethodHandle flatSwitch) {
		if (!megamorphic) setTarget(flatSwitch.asType(type()));
	}
}
