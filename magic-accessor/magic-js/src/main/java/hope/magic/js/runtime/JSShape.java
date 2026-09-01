package hope.magic.js.runtime;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * V8 风格的 Hidden Class / Shape (隐藏类/形状树)。
 * 不可变结构，记录属性 ID 到槽位索引 (slotIndex) 的映射关系、字段类型以及迁移树 (Transition Tree)。
 * 采用全局稠密整数 ID (int propId) 与紧凑原生数组 (int[] keyIds)，热路径彻底消除字符串与哈希开销。
 */
public final class JSShape {

	private static final AtomicInteger SHAPE_ID_GEN = new AtomicInteger(0);

	public static final byte TYPE_UNKNOWN = 0;
	public static final byte TYPE_DOUBLE  = 1;
	public static final byte TYPE_INT     = 2;
	public static final byte TYPE_OBJECT  = 3;

	public static final JSShape ROOT = new JSShape(null, SymbolTable.NO_SYMBOL, -1, TYPE_UNKNOWN);

	public final int id;
	public final int propertyId;
	public final int[] keyIds;
	public final int[] offsets;
	public final  byte[]                     slotTypes;
	private final IntObjectMap<JSShape> transitions = new IntObjectMap<>();

	public final int cachedKeyId;
	public final int cachedOffset;

	private JSShape(JSShape parent, int propertyId, int slotIndex, byte propertyType) {
		this.id = SHAPE_ID_GEN.getAndIncrement();
		this.propertyId = propertyId;
		this.cachedKeyId = this.propertyId;
		this.cachedOffset = slotIndex;

		int totalCount = (parent == null ? 0 : parent.keyIds.length) + (this.propertyId >= 0 && slotIndex >= 0 ? 1 : 0);
		this.keyIds = new int[totalCount];
		this.offsets = new int[totalCount];
		this.slotTypes = new byte[totalCount];

		if (parent != null && parent.keyIds.length > 0) {
			System.arraycopy(parent.keyIds, 0, this.keyIds, 0, parent.keyIds.length);
			System.arraycopy(parent.offsets, 0, this.offsets, 0, parent.offsets.length);
			System.arraycopy(parent.slotTypes, 0, this.slotTypes, 0, parent.slotTypes.length);
		}
		if (this.propertyId >= 0 && slotIndex >= 0) {
			this.keyIds[totalCount - 1] = this.propertyId;
			this.offsets[totalCount - 1] = slotIndex;
			this.slotTypes[totalCount - 1] = propertyType;
		}
	}

	/**
	 * 整数属性 ID 极速查找（单周期整数比较 + 连续内存预取）
	 */
	public int getOffset(int propId) {
		if (this.cachedKeyId == propId && propId >= 0) return this.cachedOffset;
    int[] k = this.keyIds;
    // JS 编程中，最近添加/后添加的属性被访问的概率通常更高
    for (int i = k.length - 1; i >= 0; i--) {
        if (k[i] == propId) {
            return this.offsets[i];
        }
    }
    return -1;
	}

	/**
	 * 字符串兼容查找接口
	 */
	public int getOffset(String key) {
		if (key == null) return -1;
		return getOffset(SymbolTable.id(key));
	}

	public byte getSlotType(int offset) {
		if (offset >= 0 && offset < slotTypes.length) {
			return slotTypes[offset];
		}
		return TYPE_UNKNOWN;
	}

	public JSShape addProperty(int propId) {
		return addProperty(propId, TYPE_UNKNOWN);
	}

	public JSShape addProperty(int propId, byte type) {
		return transitions.computeIfAbsent((propId << 3) | (type & 0x07),
		 k -> new JSShape(this, propId, this.keyIds.length, type));
	}

	public JSShape addProperty(String key) {
		return addProperty(SymbolTable.id(key), TYPE_UNKNOWN);
	}

	public JSShape addProperty(String key, byte type) {
		return addProperty(SymbolTable.id(key), type);
	}

	public int propertyCount() {
		return keyIds.length;
	}

	public int[] getKeyIds() {
		return keyIds;
	}

	public Set<String> keys() {
		Set<String> set = new LinkedHashSet<>(keyIds.length);
		for (int id : keyIds) {
			String name = SymbolTable.name(id);
			if (name != null) set.add(name);
		}
		return Collections.unmodifiableSet(set);
	}
}
