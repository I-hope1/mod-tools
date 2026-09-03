package hope.magic.js.runtime;

import hope.magic.js.runtime.JSLinker.PolySnapshot;

import java.lang.invoke.*;
import java.util.*;

public class ChainedCallSite extends MutableCallSite {
	public static final int          MAX_CHAIN_DEPTH = 5; // Shape 种类 <= 5 时使用链式 Guard (覆盖常见 4~5 形态多态), > 5 时自动演化为 Megamorphic 缓存表
	private             int          chainDepth      = 0;
	private volatile    boolean      megamorphic     = false;
	private             MethodHandle megamorphicTarget;

	// Offset-Equivalent IC (同偏移多态状态)
	private int     commonOffset     = -1;
	private byte    commonType       = -1;
	private boolean offsetEquivalent = true;

	/** shape → observed-offset（插入有序，用于 tableSwitch 构造） */
	private final LinkedHashMap<JSShape, Integer> shapeOffsetMap = new LinkedHashMap<>(4);

	// Megamorphic 多槽直接映射表 (Direct Mapped Fast Shape->Offset Cache)
	public static final int       CACHE_SIZE   = 8;
	public static final int       CACHE_MASK  = CACHE_SIZE - 1;
	public static final VarHandle CACHE_VH    = MethodHandles.arrayElementVarHandle(long[].class);
	public final        long[]    directCache = new long[CACHE_SIZE];

	public static long packCacheEntry(int shapeId, byte type, int offset) {
		return ((long) shapeId << 32) | (((long) type & 0xFF) << 24) | (offset & 0xFFFFFFL);
	}
	public static int unpackShapeId(long entry) { return (int) (entry >>> 32); }
	public static byte unpackType(long entry) { return (byte) ((entry >>> 24) & 0xFF); }
	public static int unpackOffset(long entry) { return (int) (entry & 0xFFFFFFL); }

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
		if (!shapeOffsetMap.containsKey(shape)) {
			shapeOffsetMap.put(shape, offset);
			if (commonOffset == -1) {
				commonOffset = offset;
				commonType = type;
			} else if (commonOffset != offset || commonType != type) {
				offsetEquivalent = false;
			}
		}
	}

	public boolean isOffsetEquivalent() {
		return offsetEquivalent && commonOffset >= 0 && shapeOffsetMap.size() >= 2;
	}

	public int getCommonOffset() {
		return commonOffset;
	}

	public byte getCommonType() {
		return commonType;
	}

	/** 返回已观测到的 Shape 列表（保持插入顺序）。 */
	public List<JSShape> getObservedShapes() {
		return new ArrayList<>(shapeOffsetMap.keySet());
	}

	/** 快照当前所有 (JSShape, offset) 对（插入顺序），用于构建 tableSwitch。 */
	public synchronized PolySnapshot snapshotPoly() {
		int       n       = shapeOffsetMap.size();
		JSShape[] shapes  = new JSShape[n];
		int[]     offsets = new int[n];
		int       i       = 0;
		for (Map.Entry<JSShape, Integer> e : shapeOffsetMap.entrySet()) {
			shapes[i] = e.getKey();
			offsets[i] = e.getValue();
			i++;
		}
		return new PolySnapshot(shapes, offsets);
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
