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

	private static final AtomicInteger SHAPE_ID_GEN = new AtomicInteger(0);

	public static final byte TYPE_UNKNOWN = 0;
	public static final byte TYPE_DOUBLE  = 1;
	public static final byte TYPE_INT     = 2;
	public static final byte TYPE_OBJECT  = 3;

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
		this.mask = (this.id < 64) ? (1L << this.id) : 0L;
		this.propertyId = propId;
		this.propertyType = propType;
		int count = (parent == null ? 0 : parent.propertyCount) + (propId >= 0 ? 1 : 0);
		this.propertyCount = count;

		if (parent == null) {
			this.k0 = -1; this.t0 = 0;
			this.k1 = -1; this.t1 = 0;
			this.k2 = -1; this.t2 = 0;
			this.k3 = -1; this.t3 = 0;
			this.overflowKeys = null;
			this.overflowTypes = null;
		} else {
			this.k0 = (count == 1) ? propId : parent.k0;
			this.t0 = (count == 1) ? propType : parent.t0;
			this.k1 = (count == 2) ? propId : parent.k1;
			this.t1 = (count == 2) ? propType : parent.t1;
			this.k2 = (count == 3) ? propId : parent.k2;
			this.t2 = (count == 3) ? propType : parent.t2;
			this.k3 = (count == 4) ? propId : parent.k3;
			this.t3 = (count == 4) ? propType : parent.t3;

			if (count <= 4) {
				this.overflowKeys = null;
				this.overflowTypes = null;
			} else {
				int overflowLen = count - 4;
				int[] ofKeys = new int[overflowLen];
				byte[] ofTypes = new byte[overflowLen];
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
			if (of[i] == propId) return i + 4;
		}
		return -1;
	}

	public int getOffset(String key) {
		if (key == null) return -1;
		return getOffset(SymbolTable.id(key));
	}

	public byte getSlotType(int offset) {
		return switch (offset) {
			case 0 -> t0;
			case 1 -> t1;
			case 2 -> t2;
			case 3 -> t3;
			default -> (overflowTypes != null && offset - 4 < overflowTypes.length)
			 ? overflowTypes[offset - 4]
			 : TYPE_UNKNOWN;
		};
	}

	// 迁移树构建 (极简编码，快路径 < 28 字节，100% C2 内联)

	public JSShape addProperty(int propId, byte type) {
		int encoded = (propId << 3) | type;
		if (this.singleKey == encoded) {
			return this.singleTransition;
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
			this.singleKey = encoded;
			this.singleTransition = next;
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
			default -> (overflowKeys != null && index - 4 < overflowKeys.length)
			 ? overflowKeys[index - 4]
			 : -1;
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