package hope.magic.js.runtime;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 高性能隐藏类 (Shape / Hidden Class)。
 * 特性：
 * 1. 针对 <= 4 个属性的小对象实现 0 数组分配（字段直接内联在 Shape 体内）。
 * 2. 消除多余的 offsets 数组（offset 恒等于属性索引）。
 * 3. 采用单迁移内联缓存（Single-Transition Inline），消灭 Map 实例化与 Lambda 闭包分配。
 * 4. 支持物理 Offset 直通，配合 Unsafe 达成 1 指令寻址。
 */
public final class JSShape {
	private static final AtomicInteger BUILTIN_ID_GEN = new AtomicInteger(-1);
	private static final AtomicInteger USER_ID_GEN    = new AtomicInteger(0);
	// 架构优化说明：
	// 原 VAR_HANDLES (ConcurrentHashMap<String, VarHandle>) 与 casIC() 属于早期单内联缓存（Single-IC）
	// 的动态原子 CAS 更新机制。当前引擎已全面进化为 ChainedCallSite 多态内联链与聚合槽位/掩码跳转表，
	// 属性寻址全量由运行时方法句柄 (SlotMH / Unsafe) 接管，casIC 已无任何外部调用方。
	// 此处废弃并移除无用的 VAR_HANDLES 缓存与 casIC 方法，消除死代码并减轻静态类加载开销。

	// 语义化控制常量
	public static final int  BITMASK_MAX_SHAPES          = 64;
	public static final int  PRECOMPUTED_SHAPES_CAPACITY = 65536;
	public static final int  INLINE_PROPERTY_CAPACITY    = 4;
	public static final int  TRANSITION_TYPE_SHIFT       = 6;
	public static final int  TRANSITION_TYPE_MASK        = 0x3F;

	public static final byte TYPE_UNKNOWN = 0;
	public static final byte TYPE_DOUBLE  = 1;
	public static final byte TYPE_INT     = 2;
	public static final byte TYPE_OBJECT  = 3;

	public static final byte FLAG_ACCESSOR         = 1 << 2; // 0x04: 访问器属性 (getter/setter)
	public static final byte FLAG_NOT_WRITABLE     = 1 << 3; // 0x08: 只读属性 (writable: false)
	public static final byte FLAG_NOT_ENUMERABLE   = 1 << 4; // 0x10: 不可枚举 (enumerable: false)
	public static final byte FLAG_NOT_CONFIGURABLE = 1 << 5; // 0x20: 不可配置 (configurable: false)
	public static final byte TYPE_MASK             = 0x03;   // 基础类型掩码

	public static volatile JSShape[]     PRECOMPUTED_SHAPES = new JSShape[PRECOMPUTED_SHAPES_CAPACITY];
	private static final   AtomicInteger PRECOMPUTED_ID     = new AtomicInteger(0);

	/** 返回预计算的Shape数组索引 */
	public static synchronized int registerPrecomputedShape(JSShape shape) {
		int id = PRECOMPUTED_ID.getAndIncrement();
		if (id >= PRECOMPUTED_SHAPES.length) {
			PRECOMPUTED_SHAPES = Arrays.copyOf(PRECOMPUTED_SHAPES, Math.max(PRECOMPUTED_SHAPES.length * 2, id + 1));
		}
		PRECOMPUTED_SHAPES[id] = shape;
		return id;
	}

	public static final JSShape ROOT = new JSShape(null, SymbolTable.NO_SYMBOL, TYPE_UNKNOWN, false);

	public final  int     id;
	public final  long    mask;            // 单指令位掩码 (1L << id，当 0 <= id < 64 时有效)
	public final  boolean hasAccessors;
	public final  int     propertyCount;

	public boolean isBuiltin() {
		return id < 0;
	}

	// In-Shape 内联 0~3 键 (涵盖 90%+ 的小对象，0 额外数组堆分配)
	public final int k0, k1, k2, k3;
	public final byte t0, t1, t2, t3;

	// 仅当属性 > 4 时才降级分配的溢出数组
	public final int[]  overflowKeys;
	public final byte[] overflowTypes;

	// 单迁移内联缓存 (Single Transition Inline - V8 核心优化)
	private volatile int                   singleKey        = -1;
	private volatile JSShape               singleTransition = null;
	private volatile IntObjectMap<JSShape> multiTransitions = null;

	private JSShape(JSShape parent, int propId, byte propType) {
		this(parent, propId, propType, parent != null && parent.isBuiltin());
	}

	private JSShape(JSShape parent, int propId, byte propType, boolean isBuiltin) {
		this.hasAccessors = (parent != null && parent.hasAccessors) || ((propType & FLAG_ACCESSOR) != 0);
		this.id = isBuiltin ? BUILTIN_ID_GEN.getAndDecrement() : USER_ID_GEN.getAndIncrement();
		this.mask = (this.id >= 0 && this.id < BITMASK_MAX_SHAPES) ? (1L << this.id) : 0L;
		int count = (parent == null ? 0 : parent.propertyCount) + (propId >= 0 ? 1 : 0);
		this.propertyCount = count;

		if (parent == null) {
			this.k0 = -1;
			this.t0 = 0;
			this.k1 = -1;
			this.t1 = 0;
			this.k2 = -1;
			this.t2 = 0;
			this.k3 = -1;
			this.t3 = 0;
			this.overflowKeys = null;
			this.overflowTypes = null;
		} else {
			this.k0 = (count == 1) ? propId : parent.k0;
			this.t0 = (count == 1) ? propType : parent.t0;
			this.k1 = (count == 2) ? propId : parent.k1;
			this.t1 = (count == 2) ? propType : parent.t1;
			this.k2 = (count == 3) ? propId : parent.k2;
			this.t2 = (count == 3) ? propType : parent.t2;
			this.k3 = (count == INLINE_PROPERTY_CAPACITY) ? propId : parent.k3;
			this.t3 = (count == INLINE_PROPERTY_CAPACITY) ? propType : parent.t3;

			if (count <= INLINE_PROPERTY_CAPACITY) {
				this.overflowKeys = null;
				this.overflowTypes = null;
			} else {
				int    overflowLen = count - INLINE_PROPERTY_CAPACITY;
				int[]  ofKeys      = new int[overflowLen];
				byte[] ofTypes     = new byte[overflowLen];
				if (parent.overflowKeys != null) {
					System.arraycopy(parent.overflowKeys, 0, ofKeys, 0, parent.overflowKeys.length);
					System.arraycopy(parent.overflowTypes, 0, ofTypes, 0, parent.overflowTypes.length);
				}
				ofKeys[overflowLen - 1] = propId;
				ofTypes[overflowLen - 1] = propType;
				this.overflowKeys = ofKeys;
				this.overflowTypes = ofTypes;
			}
		}
	}

	public static int getNextUserId() { return USER_ID_GEN.get(); }
	public static int getNextBuiltinId() { return BUILTIN_ID_GEN.get(); }

	/**
	 * 一次性烘焙终态 Shape：跳过所有中间过渡 Shape，直接生成最终形态。
	 * 只分配 1 个负数内置 Shape ID，掩码恒为 0L（不占用宝贵的 0..63 位掩码空间）。
	 */
	public static JSShape createStaticPrototypeShape(List<String> propNames) {
		return createStaticPrototypeShape(null, propNames);
	}

	public static JSShape createStaticPrototypeShape(JSShape parentProtoShape, List<String> propNames) {
		int    n       = propNames.size();
		int[]  propIds = new int[n];
		byte[] types   = new byte[n];

		for (int i = 0; i < n; i++) {
			propIds[i] = SymbolTable.symbolId(propNames.get(i));
			types[i] = (byte) (TYPE_OBJECT | FLAG_NOT_ENUMERABLE);
		}

		return new JSShape(propIds, types, true);
	}

	public static JSShape createStaticPrototypeShape(List<String> propNames, byte[] types) {
		int   n       = propNames.size();
		int[] propIds = new int[n];

		for (int i = 0; i < n; i++) {
			propIds[i] = SymbolTable.symbolId(propNames.get(i));
		}

		return new JSShape(propIds, types, true);
	}

	private JSShape(int[] propIds, byte[] types, boolean isBuiltin) {
		boolean hasAcc = false;
		for (byte t : types) {
			if ((t & FLAG_ACCESSOR) != 0) {
				hasAcc = true;
				break;
			}
		}
		this.hasAccessors = hasAcc;
		this.id = isBuiltin ? BUILTIN_ID_GEN.getAndDecrement() : USER_ID_GEN.getAndIncrement();
		this.mask = (this.id >= 0 && this.id < BITMASK_MAX_SHAPES) ? (1L << this.id) : 0L;
		int count = propIds.length;
		this.propertyCount = count;

		this.k0 = count > 0 ? propIds[0] : -1;
		this.t0 = count > 0 ? types[0] : 0;
		this.k1 = count > 1 ? propIds[1] : -1;
		this.t1 = count > 1 ? types[1] : 0;
		this.k2 = count > 2 ? propIds[2] : -1;
		this.t2 = count > 2 ? types[2] : 0;
		this.k3 = count > 3 ? propIds[3] : -1;
		this.t3 = count > 3 ? types[3] : 0;

		if (count <= INLINE_PROPERTY_CAPACITY) {
			this.overflowKeys = null;
			this.overflowTypes = null;
		} else {
			int    overflowLen = count - INLINE_PROPERTY_CAPACITY;
			int[]  ofKeys      = new int[overflowLen];
			byte[] ofTypes     = new byte[overflowLen];
			System.arraycopy(propIds, INLINE_PROPERTY_CAPACITY, ofKeys, 0, overflowLen);
			System.arraycopy(types, INLINE_PROPERTY_CAPACITY, ofTypes, 0, overflowLen);
			this.overflowKeys = ofKeys;
			this.overflowTypes = ofTypes;
		}
	}

	// 快速查找路径 (Fast Path: 严格 <= 22 字节，无条件 C2 JIT 内联)

	public int getOffset(int propId) {
		if (k0 == propId) return 0;
		if (k1 == propId) return 1;
		return getOffsetRest(propId);
	}

	private int getOffsetRest(int propId) {
		if (k2 == propId) return 2;
		if (k3 == propId) return 3;
		return getOverflowOffset(propId);
	}

	private int getOverflowOffset(int propId) {
		int[] of = this.overflowKeys;
		return of == null ? -1 : scanOverflow(of, propId);
	}

	private static int scanOverflow(int[] of, int propId) {
		for (int i = 0; i < of.length; i++) {
			if (of[i] == propId) return i + INLINE_PROPERTY_CAPACITY;
		}
		return -1;
	}

	public int getOffset(String key) {
		if (key == null) return -1;
		int symId = SymbolTable.lookupId(key); // 不注册key
		return symId == SymbolTable.NO_SYMBOL ? -1 : getOffset(symId);
	}

	/** 直接复用 {@link #getPropertyId} */
	public boolean hasPropertyAt(int propId, int offset) {
		return propId >= 0 && getPropertyId(offset) == propId;
	}

	/** Fast-Path */
	public byte getSlotType(int offset) {
		if (offset == 0) return t0;
		if (offset == 1) return t1;
		return getSlotTypeRest(offset);
	}

	private byte getSlotTypeRest(int offset) {
		if (offset == 2) return t2;
		if (offset == 3) return t3;
		return getOverflowSlotType(offset);
	}

	private byte getOverflowSlotType(int offset) {
		int    ofIdx = offset - INLINE_PROPERTY_CAPACITY;
		byte[] of    = this.overflowTypes;
		return (of != null && ofIdx >= 0 && ofIdx < of.length) ? of[ofIdx] : TYPE_UNKNOWN;
	}

	/** Fast-Path */
	public int getPropertyId(int offset) {
		if (offset == 0) return k0;
		if (offset == 1) return k1;
		return getPropertyIdRest(offset);
	}

	private int getPropertyIdRest(int offset) {
		if (offset == 2) return k2;
		if (offset == 3) return k3;
		return getOverflowPropertyId(offset);
	}

	private int getOverflowPropertyId(int offset) {
		int   ofIdx = offset - INLINE_PROPERTY_CAPACITY;
		int[] of    = this.overflowKeys;
		return (of != null && ofIdx >= 0 && ofIdx < of.length) ? of[ofIdx] : SymbolTable.NO_SYMBOL;
	}

	/**
	 * 哨兵编码值：次高 5 位为 1 (Bits 30..26)，其余位为 0。
	 * propId 限制为 {@link SymbolTable#MAX_ID} (1 << 24)，最大合法 encoded (0x3FFFFFFF) 严格小于 SENTINEL_ENCODED (0x7C000000)，
	 * 保证该值在数学和架构上绝对不可达，专用于通过冷分支膨胀字节码阻断 C2 JIT 内联。
	 */
	public static final int SENTINEL_ENCODED = 0x7C000000;
	public static final int UPDATE_TYPE_TAG  = 0x80000000;

	public static int encodeKey(int propId, byte type) {
		assert (type >= 0 && (type & ~TRANSITION_TYPE_MASK) == 0) : "Invalid property type: " + type;
		assert (type & FLAG_ACCESSOR) == 0 || (type & TYPE_MASK) == 0;
		assert propId >= 0 : "Invalid propId: " + propId;
		int encoded = (propId << TRANSITION_TYPE_SHIFT) | (type & TRANSITION_TYPE_MASK);
		assert encoded != SENTINEL_ENCODED : "Mathematical impossibility violated: encoded collided with SENTINEL_ENCODED";
		return encoded;
	}

	public static int encodeUpdateKey(int offset, byte newType) {
		assert (newType >= 0 && (newType & ~TRANSITION_TYPE_MASK) == 0) : "Invalid property type: " + newType;
		assert (newType & FLAG_ACCESSOR) == 0 || (newType & TYPE_MASK) == 0;
		assert offset >= 0 : "Invalid offset: " + offset;
		return UPDATE_TYPE_TAG | (offset << TRANSITION_TYPE_SHIFT) | (newType & TRANSITION_TYPE_MASK);
	}

	// 标志位方法
	public boolean isAccessor(int offset) {
		return (getSlotType(offset) & FLAG_ACCESSOR) != 0;
	}
	public boolean isWritable(int offset) {
		return (getSlotType(offset) & FLAG_NOT_WRITABLE) == 0;
	}
	public boolean isEnumerable(int offset) {
		return (getSlotType(offset) & FLAG_NOT_ENUMERABLE) == 0;
	}
	public boolean isConfigurable(int offset) {
		return (getSlotType(offset) & FLAG_NOT_CONFIGURABLE) == 0;
	}

	public byte getBaseType(int offset) {
		return (byte) (getSlotType(offset) & TYPE_MASK);
	}

	public JSShape updatePropertyType(int offset, byte newType) {
		if (offset < 0 || offset >= propertyCount || getSlotType(offset) == newType) return this;
		int encoded = encodeUpdateKey(offset, newType);
		if (this.singleKey == encoded) {
			JSShape trans = this.singleTransition;
			if (trans != null) return trans;
		}
		return updatePropertyTypeSlow(encoded, offset, newType);
	}

	private synchronized JSShape updatePropertyTypeSlow(int encoded, int offset, byte newType) {
		if (this.singleKey == encoded && this.singleTransition != null) {
			return this.singleTransition;
		}

		if (this.multiTransitions != null) {
			JSShape cached = this.multiTransitions.get(encoded);
			if (cached != null) return cached;
		}

		// 第一条生长分支：直接装入 singleTransition，避免 new IntObjectMap
		if (this.singleTransition == null && this.multiTransitions == null) {
			JSShape next = createUpdatedShape(offset, newType);
			this.singleTransition = next;
			this.singleKey = encoded;
			return next;
		}

		// 出现分叉（第二条以上分支）：冷创建多迁移哈希表
		if (this.multiTransitions == null) {
			IntObjectMap<JSShape> map = new IntObjectMap<>();
			map.put(this.singleKey, this.singleTransition);
			this.multiTransitions = map;
		}

		JSShape next = this.multiTransitions.get(encoded);
		if (next == null) {
			next = createUpdatedShape(offset, newType);
			this.multiTransitions.put(encoded, next);
		}
		return next;
	}

	private JSShape createUpdatedShape(int offset, byte newType) {
		int    n     = propertyCount;
		int[]  keys  = getKeyIds();
		byte[] types = new byte[n];
		for (int i = 0; i < n; i++) {
			types[i] = (i == offset) ? newType : getSlotType(i);
		}
		return new JSShape(keys, types, this.isBuiltin());
	}

	// 迁移树构建 (极简编码，快路径 < 28 字节，100% C2 内联)

	public JSShape addProperty(int propId, byte type) {
		int encoded = encodeKey(propId, type);
		// 先读 volatile singleKey
		if (this.singleKey == encoded) {
			// 确保匹配后再读 volatile singleTransition，此时必定非空且已完全初始化
			JSShape trans = this.singleTransition;
			if (trans != null) return trans;
		}
		return addPropertySlow(encoded, propId, type);
	}

	private synchronized JSShape addPropertySlow(int encoded, int propId, byte type) {
		if (this.singleKey == encoded && this.singleTransition != null) {
			return this.singleTransition;
		}

		// 第一条生长分支：直接装入 singleTransition，避免 new IntObjectMap
		if (this.singleTransition == null && this.multiTransitions == null) {
			JSShape next = new JSShape(this, propId, type);
			// hb(write transition, write key) ：同一线程 program order
			// hb(write key, read key) ：volatile 语义（当读确实看到新值时）
			// hb(read key, read transition) ：同一线程 program order
			// 由传递性： hb(write transition, read transition)  严格成立。因此只要快速路径读到  singleKey  匹配，读到的  singleTransition  必定是完整初始化的非空值。
			// 必须先写 singleTransition 后写 singleKey，利用 volatile 内存屏障保证其他线程读到 singleKey 时 transition 必定非空
			this.singleTransition = next;
			this.singleKey = encoded;
			return next;
		}

		// 出现分叉（第二条以上分支）：冷创建多迁移哈希表
		if (this.multiTransitions == null) {
			IntObjectMap<JSShape> map = new IntObjectMap<>();
			map.put(this.singleKey, this.singleTransition);
			this.multiTransitions = map;
		}

		JSShape next = this.multiTransitions.get(encoded);
		if (next == null) {
			next = new JSShape(this, propId, type);
			this.multiTransitions.put(encoded, next);
		}

		// 确保本慢路径方法字节码大小 > 325 字节，使 HotSpot C2 将此冷路径判定为 'hot method too big'，绝不在顶层内联
		// 让 C2 有更多预算内联其他方法
		if (encoded == SENTINEL_ENCODED) {
			switch (propId) {
				// 1-70
				case 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
				     21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
				     41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60,
				     61, 62, 63, 64, 65, 66, 67, 68, 69, 70 -> {
					return next; // 即便极端情况下触及屏障分支，也恒定返回有效 JSShape，绝不返回 null！
				}
			}
		}
		return next;
	}

	public JSShape addProperty(int propId) {
		return addProperty(propId, TYPE_UNKNOWN);
	}

	public JSShape addProperty(String key) {
		return addProperty(SymbolTable.id(key), TYPE_UNKNOWN);
	}

	public JSShape addProperty(String key, byte type) {
		return addProperty(SymbolTable.id(key), type);
	}

	public int propertyCount() {
		return propertyCount;
	}

	/** @see #getPropertyId(int) */
	public int getKeyId(int index) {
		return getPropertyId(index);
	}

	/** 不使用table switch，减少字节码体积 */
	public int[] getKeyIds() {
		int   n   = propertyCount;
		int[] all = new int[n];
		if (n > 0) all[0] = k0;
		if (n > 1) all[1] = k1;
		if (n > 2) all[2] = k2;
		if (n > 3) all[3] = k3;
		if (n > INLINE_PROPERTY_CAPACITY && overflowKeys != null) {
			System.arraycopy(overflowKeys, 0, all, INLINE_PROPERTY_CAPACITY, n - INLINE_PROPERTY_CAPACITY);
		}
		return all;
	}

	/** 返回Shape中所有属性的名称集合 */
	public Set<String> keys() {
		Set<String> set = new LinkedHashSet<>(propertyCount);
		for (int id : getKeyIds()) {
			String name = SymbolTable.name(id);
			if (name != null) set.add(name);
		}
		return Collections.unmodifiableSet(set);
	}
}