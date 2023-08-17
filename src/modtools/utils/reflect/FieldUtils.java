package modtools.utils.reflect;

import java.lang.reflect.Field;

public class FieldUtils {
	/* 获取字段，并设置override */
	public static Field getFieldAccess(Class<?> cls, String name) {
		try {
			Field field = cls.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (NoSuchFieldException e) {
			throw new RuntimeException(e);
		}
	}
}
