package modtools.utils;

import arc.Events;
import arc.files.Fi;
import arc.func.*;
import arc.scene.Element;
import arc.struct.Seq;
import arc.util.*;
import arc.util.Log.LogHandler;
import arc.util.Timer.Task;
import mindustry.game.EventType.Trigger;
import modtools.ui.IntUI;
import modtools.ui.components.Window;
import modtools.struct.TaskSet;

import java.lang.reflect.*;
import java.util.List;
import java.util.function.Consumer;

import static ihope_lib.MyReflect.unsafe;

public class Tools {
	public static TaskSet TASKS = new TaskSet();

	static {
		Events.run(Trigger.update, TASKS::exec);
	}

	public static String readFiOrEmpty(Fi fi) {
		try {
			return fi.exists() ? fi.readString() : "";
		} catch (Throwable ignored) {}
		return ">>>><ERROR><<<<";
	}

	public static String clName(Object o) {
		return o.getClass().getName();
	}

	// 去除颜色
	public static String format(String s) {
		return s.replaceAll("\\[(\\w+?)\\]", "[\u0001$1]");
	}

	public static void clone(Object from, Object to, Class<?> cls, Seq<String> blackList) {
		if (from == to) throw new IllegalArgumentException("from == to");
		if (from == null) return;
		while (cls != null && Object.class.isAssignableFrom(cls)) {
			Field[] fields = cls.getDeclaredFields();
			for (Field f : fields) {
				if (!Modifier.isStatic(f.getModifiers()) && (blackList == null || !blackList.contains(f.getName()))) {
					// if (display) Log.debug(f);
					copyValue(f, from, to);
				}
			}
			cls = cls.getSuperclass();
		}
	}
	public static void copyValue(Field f, Object from, Object to) {
		Class<?> type   = f.getType();
		long     offset = modtools.utils.reflect.FieldUtils.fieldOffset(f);
		if (int.class == type) {
			unsafe.putInt(to, offset, unsafe.getInt(from, offset));
		} else if (float.class == type) {
			unsafe.putFloat(to, offset, unsafe.getFloat(from, offset));
		} else if (double.class == type) {
			unsafe.putDouble(to, offset, unsafe.getDouble(from, offset));
		} else if (long.class == type) {
			unsafe.putLong(to, offset, unsafe.getLong(from, offset));
		} else if (char.class == type) {
			unsafe.putChar(to, offset, unsafe.getChar(from, offset));
		} else if (byte.class == type) {
			unsafe.putByte(to, offset, unsafe.getByte(from, offset));
		} else if (short.class == type) {
			unsafe.putShort(to, offset, unsafe.getShort(from, offset));
		} else if (boolean.class == type) {
			unsafe.putBoolean(to, offset, unsafe.getBoolean(from, offset));
		} else {
			Object o = unsafe.getObject(from, offset);
			/*if (f.getType().isArray()) {
				o = Arrays.copyOf((Object[]) o, Array.getLength(o));
			}*/
			unsafe.putObject(to, offset, o);
		}
	}

	/** 重复运行直到返回{@code true} */
	public static void forceRun(Boolp boolp) {
		// Log.info(Time.deltaimpl);
		Timer.schedule(new Task() {
			@Override
			public void run() {
				try {
					if (boolp.get()) cancel();
				} catch (Exception e) {
					Log.err(e);
					cancel();
				}
			}
		}, 0f, 1f, -1);
		/*Runnable[] run = {null};
		run[0] = () -> {
			Time.runTask(0, () -> {
				try {
					toRun.run();
				} catch (Exception e) {
					run[0].run();
				}
			});
		};
		run[0].run();*/
	}


	public static Class<?> box(Class<?> type) {
		if (!type.isPrimitive()) return type;
		if (type == boolean.class) return Boolean.class;
		if (type == byte.class) return Byte.class;
		if (type == char.class) return Character.class;
		if (type == short.class) return Short.class;
		if (type == int.class) return Integer.class;
		if (type == float.class) return Float.class;
		if (type == long.class) return Long.class;
		if (type == double.class) return Double.class;
		return type;
		// return TO_BOX_MAP.get(type, type);
	}
	public static Class<?> unbox(Class<?> type) {
		if (type.isPrimitive()) return type;
		if (type == Boolean.class) return boolean.class;
		if (type == Byte.class) return byte.class;
		if (type == Character.class) return char.class;
		if (type == Short.class) return short.class;
		if (type == Integer.class) return int.class;
		if (type == Float.class) return float.class;
		if (type == Long.class) return long.class;
		if (type == Double.class) return double.class;
		// it will not reach
		return type;
	}

	public static <T> T as(Object o) {
		return (T) o;
	}
	public static <T> T or(T t1, T t2) {
		return t1 == null ? t2 : t1;
	}
	public static <T> T or(T t1, Prov<T> t2) {
		return t1 == null ? t2.get() : t1;
	}
	/**
	 * 不相等就会设置值
	 * @return 是否不相等
	 */
	public static boolean CAS(long[] arr, long t) {
		if (arr[0] != t) {
			arr[0] = t;
			return true;
		}
		return false;
	}
	private static final SR sr_instance = new SR<>(null);
	public static <T> SR<T> Sr(T value) {
		return sr_instance.setv(value);
	}

	public static <T> void checknull(T t, Consumer<T> cons) {
		if (t != null) cons.accept(t);
	}
	/**
	 * 检查是否为null，
	 * @param cons     非null时执行
	 * @param nullcons 为null时执行
	 */
	public static <T> void checknull(T t, Consumer<T> cons, Runnable nullcons) {
		if (t != null) cons.accept(t);
		else nullcons.run();
	}
	public static <T> void checknull(T t, Runnable run) {
		if (t != null) run.run();
	}

	public static <T> T getOrNull(T[] arr, int i) {
		return 0 <= i && i < arr.length ? arr[i] : null;
	}

	/** 自动监控原seq */
	public static <T> Seq<T> selectUpdateFrom(Seq<T> items, Boolf<T> predicate) {
		Seq<T> arr  = new Seq<>();
		int[]  size = {items.size};
		TASKS.add(() -> {
			if (items.size != size[0]) arr.selectFrom(items, predicate);
		});
		return arr.selectFrom(items, predicate);
	}

	public static void runIgnoredException(CatchRun run) {
		try {
			run.run();
		} catch (Throwable ignored) {}
	}
	public static void runLoggedException(CatchRun run) {
		try {
			run.run();
		} catch (Throwable e) {
			Log.err(e);
		}
	}
	public static void runLoggedException(CatchRun run, Runnable unexpected) {
		try {
			run.run();
		} catch (Throwable e) {
			Log.err(e);
			unexpected.run();
		}
	}


	public static Runnable catchRun(CatchRun run) {
		return catchRun("", run, null);
	}
	public static Runnable catchRun(CatchRun run, Element el) {
		return catchRun("", run, el);
	}
	public static Runnable catchRun(String text, CatchRun run, Element el) {
		return () -> {
			try {
				run.run();
			} catch (Throwable th) {
				Window window = IntUI.showException(text, th);
				if (el != null) window.setPosition(ElementUtils.getAbsolutePos(el));
			}
		};
	}

	public static <T> Cons<T> catchCons(CCons<T> cons, Cons<Throwable> resolver) {
		return t -> {
			try {
				cons.get(t);
			} catch (Throwable e) {
				resolver.get(e);
			}
		};
	}

	public static <T> T setLogger(LogHandler logger, Prov<T> prov) {
		var prev = Log.logger;
		Log.logger = logger;
		try {
			return prov.get();
		} finally {
			Log.logger = prev;
		}
	}

	public static <T> void each(List<T> list, Cons<T> cons) {
		for (int i = list.size(); i-- > 0; ) {
			cons.get(list.get(i));
		}
	}
	public interface CatchRun {
		void run() throws Throwable;
	}
	public interface CBoolp {
		boolean get() throws Throwable;
	}
	public interface CCons<T> {
		void get(T t) throws Throwable;
	}
	public interface CProv<T> extends CProvT<T, Throwable> {}
	public interface CProvT<T, E extends Throwable> {
		T get() throws E;
	}
}

