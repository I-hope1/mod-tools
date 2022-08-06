package modtools.utils;

import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.struct.Seq;
import arc.util.Time;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static modtools_lib.MyReflect.unsafe;

public class Tools {
	public static boolean validPosInt(String text) {
		return text.matches("^\\d+(\\.\\d*)?([Ee]\\d+)?$");
	}

	public static int asInt(String text) {
		return (int) Float.parseFloat(text);
	}

	// 去除颜色
	public static String format(String s) {
		return s.replaceAll("\\[(\\w+?)\\]", "[\u0001$1]");
	}


	public static int len(String s) {
		return s.split("").length - 1;
	}

	public static Vec2 getAbsPos(Element el) {
		if (true) return el.localToStageCoordinates(new Vec2(0, 0));
		Vec2 vec2 = new Vec2(el.x, el.y);
		while (el.parent != null) {
			el = el.parent;
			vec2.add(el.x, el.y);
		}
		return vec2;
	}

	public static void clone(Object from, Object to, Class<?> cls, Seq<String> blackList) {
		if (from == to) throw new IllegalArgumentException("from == to");
		while (cls != null && Object.class.isAssignableFrom(cls)) {
			Field[] fields = cls.getDeclaredFields();
			for (Field f : fields) {
				if (!Modifier.isStatic(f.getModifiers()) && (blackList == null || !blackList.contains(f.getName()))) {
					// if (display) Log.debug(f);
					setValue(f, from, to);
				}
			}
			cls = cls.getSuperclass();
		}
	}

	public static void setValue(Field f, Object from, Object to) {
		Class<?> type = f.getType();
		long offset = unsafe.objectFieldOffset(f);
		if (int.class.equals(type)) {
			unsafe.putInt(to, offset, unsafe.getInt(from, offset));
		} else if (float.class.equals(type)) {
			unsafe.putFloat(to, offset, unsafe.getFloat(from, offset));
		} else if (double.class.equals(type)) {
			unsafe.putDouble(to, offset, unsafe.getDouble(from, offset));
		} else if (long.class.equals(type)) {
			unsafe.putLong(to, offset, unsafe.getLong(from, offset));
		} else if (char.class.equals(type)) {
			unsafe.putChar(to, offset, unsafe.getChar(from, offset));
		} else if (byte.class.equals(type)) {
			unsafe.putByte(to, offset, unsafe.getByte(from, offset));
		} else if (short.class.equals(type)) {
			unsafe.putShort(to, offset, unsafe.getShort(from, offset));
		} else if (boolean.class.equals(type)) {
			unsafe.putBoolean(to, offset, unsafe.getBoolean(from, offset));
		} else {
			Object o = unsafe.getObject(from, offset);
			/*if (f.getType().isArray()) {
				o = Arrays.copyOf((Object[]) o, Array.getLength(o));
			}*/
			unsafe.putObject(to, offset, o);
		}
	}

	public static void forceRun(Runnable toRun) {
		Runnable[] run = {null};
		run[0] = () -> {
			Time.runTask(0, () -> {
				try {
					toRun.run();
				} catch (Exception e) {
					run[0].run();
				}
			});
		};
		run[0].run();
	}
}
