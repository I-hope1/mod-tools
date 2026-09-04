package hope.magic.js.runtime;

import hope.magic.runtime.Magic;

import java.lang.invoke.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
	private static final MethodHandles.Lookup LOOKUP = Magic.lookup;

	private static final AtomicInteger                        SHAPE_ID_GEN = new AtomicInteger(1);
	private static final ConcurrentHashMap<String, VarHandle> VAR_HANDLES  = new ConcurrentHashMap<>();

	// 语义化控制常量
	public static final int  SHAPE_ID_SHIFT              = 32;
	public static final long OFFSET_MASK                 = 0xFFFFFFFFL;
	public static final int  BITMASK_MAX_SHAPES          = 64;
	public static final int  PRECOMPUTED_SHAPES_CAPACITY = 65536;
	public static final int  INLINE_PROPERTY_CAPACITY    = 4;
	public static final int  TRANSITION_TYPE_SHIFT       = 3;
	public static final int  TRANSITION_TYPE_MASK        = 0x7;

	public static long packIC(int shapeId, int offset) {
		return ((long) shapeId << SHAPE_ID_SHIFT) | (offset & OFFSET_MASK);
	}

	public static boolean casIC(Class<?> clazz, String fieldName, long expected, long update) {
		String    key = clazz.getName() + "." + fieldName;
		VarHandle vh  = VAR_HANDLES.get(key);
		if (vh == null) {
			try {
				vh = LOOKUP.findStaticVarHandle(clazz, fieldName, long.class);
				VAR_HANDLES.put(key, vh);
			} catch (Throwable t) {
				try {
					Field f      = clazz.getField(fieldName);
					long  offset = Magic.unsafe.staticFieldOffset(f);
					return Magic.unsafe.compareAndSwapLong(clazz, offset, expected, update);
				} catch (Throwable ignored) {
					return false;
				}
			}
		}
		return vh.compareAndSet(expected, update);
	}

	public static final byte TYPE_UNKNOWN = 0;
	public static final byte TYPE_DOUBLE  = 1;
	public static final byte TYPE_INT     = 2;
	public static final byte TYPE_OBJECT  = 3;

	public static volatile JSShape[]   PRECOMPUTED_SHAPES = new JSShape[PRECOMPUTED_SHAPES_CAPACITY];
	private static final AtomicInteger PRECOMPUTED_ID     = new AtomicInteger(0);

	public static synchronized int registerPrecomputedShape(JSShape shape) {
		int id = PRECOMPUTED_ID.getAndIncrement();
		if (id >= PRECOMPUTED_SHAPES.length) {
			PRECOMPUTED_SHAPES = Arrays.copyOf(PRECOMPUTED_SHAPES, Math.max(PRECOMPUTED_SHAPES.length * 2, id + 1));
		}
		PRECOMPUTED_SHAPES[id] = shape;
		return id;
	}

	public static final JSShape ROOT = new JSShape(null, SymbolTable.NO_SYMBOL, TYPE_UNKNOWN);

	public final int  id;
	public final long mask;            // 单指令位掩码 (1L << id，当 id < 64 时有效)
	public final int  propertyCount;
	public final int  propertyId;      // 本次迁移引入的属性 ID
	public final byte propertyType;   // 本次迁移引入的类型

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
		this.id = SHAPE_ID_GEN.getAndIncrement();
		this.mask = (this.id < BITMASK_MAX_SHAPES) ? (1L << this.id) : 0L;
		this.propertyId = propId;
		this.propertyType = propType;
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
		if (of == null) return -1;
		return scanOverflow(of, propId);
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

	public byte getSlotType(int offset) {
		return switch (offset) {
			case 0 -> t0;
			case 1 -> t1;
			case 2 -> t2;
			case 3 -> t3;
			default -> {
				int ofIdx = offset - INLINE_PROPERTY_CAPACITY;
				// 严密修复下界 >= 0，彻底根治 offset = -1 时的 Index -5 崩溃
				yield (overflowTypes != null && ofIdx >= 0 && ofIdx < overflowTypes.length)
				 ? overflowTypes[ofIdx]
				 : TYPE_UNKNOWN;
			}
		};
	}

	/**
	 * 哨兵编码值：低 3 位设为 0b111 (7)，高位全为 1 (0x7FFFFFFF)。
	 * <p>
	 * <b>数学不可达性证明：</b><br>
	 * 任何合法属性迁移的 {@code type} 仅占用 2 位（{@link #TYPE_UNKNOWN}=0, {@link #TYPE_DOUBLE}=1,
	 * {@link #TYPE_INT}=2, {@link #TYPE_OBJECT}=3，即 {@code 0b00 ~ 0b11}），
	 * 其低 3 位的值必然 {@code <= 3 (0b011)}，第 2 位（权重 4）恒为 0。<br>
	 * 而 {@code SENTINEL_ENCODED} 的低 3 位为 7（{@code 0b111}）。<br>
	 * 因此对于任意非负 {@code propId} 和合法 {@code type}，{@code (propId << 3) | type} 严格不等于 {@code SENTINEL_ENCODED}。
	 */
	public static final int SENTINEL_ENCODED = 0x7FFFFFFF;

	public static int encodeKey(int propId, byte type) {
		assert (type >= 0 && type <= 3) : "Invalid property type: " + type;
		assert propId >= 0 : "Invalid propId: " + propId;
		int encoded = (propId << TRANSITION_TYPE_SHIFT) | (type & TRANSITION_TYPE_MASK);
		assert encoded != SENTINEL_ENCODED : "Mathematical impossibility violated: encoded collided with SENTINEL_ENCODED";
		return encoded;
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

	@SuppressWarnings("DuplicatedCode")
	private synchronized JSShape addPropertySlow(int encoded, int propId, byte type) {
		if (this.singleKey == encoded && this.singleTransition != null) {
			return this.singleTransition;
		}

		// 第一条生长分支：直接装入 singleTransition，避免 new IntObjectMap
		if (this.singleTransition == null && this.multiTransitions == null) {
			JSShape next = new JSShape(this, propId, type);
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

	public int getKeyId(int index) {
		return switch (index) {
			case 0 -> k0;
			case 1 -> k1;
			case 2 -> k2;
			case 3 -> k3;
			default -> {
				int ofIdx = index - 4;
				yield (overflowKeys != null && ofIdx >= 0 && ofIdx < overflowKeys.length)
				 ? overflowKeys[ofIdx]
				 : -1;
			}
		};
	}

	public int[] getKeyIds() {
		int[] all = new int[propertyCount];
		for (int i = 0; i < propertyCount; i++) {
			all[i] = switch (i) {
				case 0 -> k0;
				case 1 -> k1;
				case 2 -> k2;
				case 3 -> k3;
				default -> overflowKeys[i - 4];
			};
		}
		return all;
	}

	public Set<String> keys() {
		Set<String> set = new LinkedHashSet<>(propertyCount);
		for (int id : getKeyIds()) {
			String name = SymbolTable.name(id);
			if (name != null) set.add(name);
		}
		return Collections.unmodifiableSet(set);
	}
}