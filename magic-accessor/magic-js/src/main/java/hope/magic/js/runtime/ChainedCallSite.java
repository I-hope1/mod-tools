package hope.magic.js.runtime;

import hope.magic.js.runtime.JSLinker.PolySnapshot;

import java.lang.invoke.*;
import java.util.*;

public class ChainedCallSite extends MutableCallSite {
	public static final int          MAX_CHAIN_DEPTH = 5; // Shape 种类 <= 5 时使用链式 Guard (覆盖常见 4~5 形态多态), > 5 时自动演化为 Megamorphic 缓存表
	private             int          chainDepth      = 0;
	private volatile    boolean      megamorphic     = false;
	private             MethodHandle megamorphicTarget;

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

	/** shape → observed-offset（插入有序，用于 tableSwitch 构造） */
	private final LinkedHashMap<JSShape, Integer> shapeOffsetMap = new LinkedHashMap<>(4);

	// Megamorphic 多槽直接映射表 (Direct Mapped Fast Shape->Offset Cache)
	public static final int CACHE_SIZE = 64;
	public static final int PHI_32     = 0x9E3779B9; // 黄金比例常数

	/** 极速 32 位黄金比例散列：单条 imul + 单条 shr 汇编指令 */
	public static int cacheIndex(int shapeId) {
		return (shapeId * PHI_32) >>> 26; // 32 - 6 = 26，输出 [0, 63]
	}

	// directCache
	public static final VarHandle CACHE_VH    = MethodHandles.arrayElementVarHandle(long[].class);
	public final        long[]    directCache = new long[CACHE_SIZE];


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
    }
    // 注意：即使 polyCount >= MAX_CHAIN_DEPTH，
    // 只要它的 offset == commonOffset，offsetEquivalent 就绝不应该被置为 false！
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
			if (megamorphicTarget != null) setTarget(megamorphicTarget.asType(type()));
			return false;
		}
		MethodHandle guard = MethodHandles.guardWithTest(test, fastTarget.asType(type()), getTarget());
		setTarget(guard);
		return true;
	}

	/**
	 * 挂载扁平多态 Jump-Table 守卫（单层 switch，彻底消除 N 层 LambdaForm 嵌套）。
	 * 仅在 chainDepth >= 2 && !offsetEquivalent 时由 Fallback 方法调用。
	 */
	public synchronized void installFlatPolyGuard(MethodHandle flatSwitch) {
		if (!megamorphic) setTarget(flatSwitch.asType(type()));
	}
}
