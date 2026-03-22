package nipx.profiler;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通过反射从 Mindustry 实体对象提取短可读标识符，供火焰图 key 使用。
 *
 * <p>key 格式示例：
 * <ul>
 *   <li>Building → {@code "titanium-conveyor@(12,5)"}
 *   <li>Unit     → {@code "mono#3842"}
 *   <li>其他     → {@code "ClassName#identityHash"}
 * </ul>
 *
 * <p>反射结果缓存在 {@link #fieldCache}，稳定运行后每次调用开销仅为 Map 查找 + 字段 get。
 * 拼接 key 字符串不可避免地分配内存，但该路径仅在首次遇到新实体时触发（之后 computeIfAbsent 命中缓存）。
 */
public class EntityKeyExtractor {

	// ── 反射字段缓存：Class → 已解析的字段组（不存在则为 MISSING） ──────────
	private static final ConcurrentHashMap<Class<?>, FieldBundle> fieldCache = new ConcurrentHashMap<>();

	/** 哨兵：标记该类已确认无法提取有意义字段，后续直接走 fallback。*/
	private static final FieldBundle MISSING = new FieldBundle(null, null, null, null);

	record FieldBundle(Field block, Field blockName, Field tileX, Field tileY) {}

	// ── 公共 API ──────────────────────────────────────────────────────────────

	/**
	 * 提取实体的短 key。
	 * <p>结果不含方法名，由调用方拼接：{@code key(self) + "." + methodName}。
	 */
	public static String key(Object self) {
		if (self == null) return "null";
		Class<?> cls = self.getClass();
		FieldBundle fb = fieldCache.computeIfAbsent(cls, EntityKeyExtractor::resolve);
		if (fb == MISSING) return simpleName(cls);
		return buildKey(self, fb, cls);
	}

	// ── 字段解析（每个 Class 只做一次） ──────────────────────────────────────

	private static FieldBundle resolve(Class<?> cls) {
		// 尝试 Building 模式：需要 block（有 name 字段）+ tileX/tileY
		Field block     = findField(cls, "block");
		Field blockName = block != null ? findField(block.getType(), "name") : null;
		Field tileX     = findFieldByName(cls, "tileX", "x"); // Building.tileX or fallback
		Field tileY     = findFieldByName(cls, "tileY", "y");

		if (block != null && blockName != null) {
			// Building 模式：找到 block.name
			return new FieldBundle(block, blockName, tileX, tileY);
		}

		// 尝试 Unit 模式：type 字段有 name
		Field type     = findField(cls, "type");
		Field typeName = type != null ? findField(type.getType(), "name") : null;
		if (type != null && typeName != null) {
			// 复用 block/blockName 槽存放 type/typeName
			Field idField = findFieldByName(cls, "id");
			return new FieldBundle(type, typeName, idField, null);
		}

		return MISSING;
	}

	// ── Key 构建 ─────────────────────────────────────────────────────────────

	private static String buildKey(Object self, FieldBundle fb, Class<?> cls) {
		try {
			// block/type 名称
			Object container = fb.block().get(self);
			if (container == null) return simpleName(cls);
			Object name = fb.blockName().get(container);
			String baseName = name != null ? name.toString() : simpleName(cls);

			if (fb.tileY() != null) {
				// Building 模式：加坐标
				Object tx = fb.tileX() != null ? fb.tileX().get(self) : null;
				Object ty = fb.tileY().get(self);
				if (tx != null && ty != null) return baseName + "@(" + tx + "," + ty + ")";
				return baseName;
			} else if (fb.tileX() != null) {
				// Unit 模式：加 id
				Object id = fb.tileX().get(self);
				return baseName + (id != null ? "#" + id : "");
			}
			return baseName;
		} catch (IllegalAccessException e) {
			return simpleName(cls);
		}
	}

	// ── 反射工具 ─────────────────────────────────────────────────────────────

	/** 在类及父类中查找指定名称的第一个字段，并设为可访问。*/
	private static Field findField(Class<?> cls, String name) {
		for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
			try {
				Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				return f;
			} catch (NoSuchFieldException ignored) {}
		}
		return null;
	}

	/** 按候选名称列表依次尝试，返回第一个找到的字段。*/
	private static Field findFieldByName(Class<?> cls, String... names) {
		for (String name : names) {
			Field f = findField(cls, name);
			if (f != null) return f;
		}
		return null;
	}

	private static String simpleName(Class<?> cls) {
		String n = cls.getSimpleName();
		return n.isEmpty() ? cls.getName() : n;
	}
}