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
import modtools.*;
import modtools.struct.TaskSet;
import modtools.ui.IntUI;
import modtools.ui.comp.Window;

import java.lang.reflect.*;
import java.util.List;
import java.util.function.Consumer;

import static ihope_lib.MyReflect.unsafe;

@SuppressWarnings("unchecked")
public class Tools {
	public static final boolean DEBUG = false;

	/** 静态的任务集合 */
	public static TaskSet TASKS = new TaskSet();

	// 静态初始化块，用于在触发更新时运行任务集合
	static {
		Events.run(Trigger.update, delegate(TASKS::exec, TASKS::isEmpty));
	}

	/**
	 * 读取指定文件，如果文件不存在则返回空字符串
	 * @param fi 文件对象
	 * @return 文件内容或空字符串
	 */
	public static String readFiOrEmpty(Fi fi) {
		try {
			return fi.exists() ? fi.readString() : "";
		} catch (Throwable ignored) { }
		return "<ERROR>";
	}

	/**
	 * 获取对象的类名（没什么用）
	 * @param o 对象
	 * @return 类名字符串
	 */
	public static String clName(Object o) {
		return o.getClass().getName();
	}

	/**
	 * 克隆对象的属性，排除黑名单中的属性
	 * @param from      源对象
	 * @param to        目标对象
	 * @param cls       类对象
	 * @param blackList 黑名单
	 */
	public static void clone(Object from, Object to, Class<?> cls, Seq<String> blackList) {
		clone(from, to, cls, f -> blackList == null || blackList.contains(f.getName()));
	}

	/**
	 * 克隆对象的属性，根据条件判断是否复制属性
	 * @param from  源对象
	 * @param to    目标对象
	 * @param cls   类对象
	 * @param boolf 条件判断函数
	 */
	public static void clone(Object from, Object to, Class<?> cls, Boolf<Field> boolf) {
		if (from == to) throw new IllegalArgumentException("from == to");
		if (from == null) return;
		while (cls != null && Object.class.isAssignableFrom(cls)) {
			Field[] fields = cls.getDeclaredFields();
			for (Field f : fields) {
				if (!Modifier.isStatic(f.getModifiers()) && boolf.get(f)) {
					// if (display) Log.debug(f);
					copyValue(f, from, to);
				}
			}
			cls = cls.getSuperclass();
		}
	}

	/**
	 * 复制字段的值
	 * @param f    字段
	 * @param from 源对象
	 * @param to   目标对象
	 */
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

	/**
	 * 重复运行直到返回{@code true}
	 * @param boolp 布尔提供者
	 */
	public static void forceRun(Boolp boolp) {
		Timer.schedule(new Task() {
			public void run() {
				try {
					if (boolp.get()) cancel();
				} catch (Throwable e) {
					Log.err(e);
					cancel();
				}
			}
		}, 0f, 0.5f, -1);
	}

	/**
	 * 自动强转对象
	 * @param o   对象
	 * @param <T> 类型
	 * @return 强转后的对象
	 */
	public static <T> T as(Object o) {
		return (T) o;
	}

	/**
	 * 返回第一个非空值
	 * @param t1  第一个值
	 * @param t2  第二个值
	 * @param <T> 类型
	 * @return 非空值？？
	 */
	public static <T> T or(T t1, T t2) {
		return t1 != null ? t1 : t2; // or(t2, Tools::_throw);
	}

	/**
	 * 返回第一个非空值，如果第一个值为空，则执行prov
	 * @param t1  第一个值
	 * @param t2  prov
	 * @param <T> 类型
	 * @return 非空值？？
	 */
	public static <T> T or(T t1, Prov<T> t2) {
		return t1 != null ? t1 : t2.get(); // or(t2.get(), Tools::_throw);
	}

	/**
	 * 检查对象是否为null，如果非null则执行Consumer
	 * @param t    对象
	 * @param cons Consumer
	 * @param <T>  类型
	 */
	public static <T> void checknull(T t, Consumer<T> cons) {
		if (t != null) cons.accept(t);
	}

	/**
	 * 检查对象是否为null，如果非null则执行Consumer，否则执行Runnable
	 * @param t        对象
	 * @param cons     非null时执行的Consumer
	 * @param nullcons 为null时执行的Runnable
	 * @param <T>      类型
	 */
	public static <T> void checknull(T t, Consumer<T> cons, Runnable nullcons) {
		if (t != null) cons.accept(t);
		else nullcons.run();
	}

	/**
	 * 检查对象是否为null，如果非null则执行Runnable
	 * @param t   对象
	 * @param run 非null时执行的Runnable
	 * @param <T> 类型
	 */
	public static <T> void checknull(T t, Runnable run) {
		if (t != null) run.run();
	}

	/**
	 * 获取数组中的元素，如果索引越界则返回null
	 * @param arr 数组
	 * @param i   索引
	 * @param <T> 类型
	 * @return 元素或null
	 */
	public static <T> T getOrNull(T[] arr, int i) {
		return 0 <= i && i < arr.length ? arr[i] : null;
	}

	/**
	 * 自动监控原seq，根据条件筛选元素
	 * @param items     原始序列
	 * @param predicate 筛选条件
	 * @param <T>       类型
	 * @return 筛选后的序列
	 */
	public static <T> Seq<T> selectUpdateFrom(Seq<T> items, Boolf<T> predicate) {
		Seq<T> arr  = new Seq<>();
		int[]  size = {items.size};
		TASKS.add(() -> {
			if (items.size != size[0]) arr.selectFrom(items, predicate);
		});
		return arr.selectFrom(items, predicate);
	}

	/**
	 * 运行带有异常捕获的任务，忽略报错
	 * @param run 要运行的任务
	 */
	public static void runIgnoredException(CatchRun run) {
		try {
			run.run();
		} catch (Throwable th) {
			if (DEBUG) Log.err(th);
		}
	}

	/**
	 * 运行带有异常的任务，捕获异常（并用UI展示异常）
	 * @param run 要运行的任务
	 */
	public static void runShowedException(CatchRun run) {
		try {
			run.run();
		} catch (Throwable e) {
			IntUI.showException(e);
			Log.err(e);
		}
	}

	/**
	 * 运行带有异常的任务，捕获异常（并记录日志）
	 * @param run 要运行的任务
	 */
	public static void runLoggedException(CatchRun run) {
		try {
			run.run();
		} catch (Throwable e) {
			Log.err(e);
		}
	}
	/**
	 * 运行带有异常的任务，捕获异常（并记录日志）
	 * @param run 要运行的任务
	 */
	public static void runLoggedException(String failText, CatchRun run) {
		try {
			run.run();
		} catch (Throwable e) {
			Log.err(failText, e);
		}
	}

	/**
	 * <p>运行带有异常的任务，捕获异常（并记录日志）</p>
	 * <p>如果发生异常，则执行unexpected</p>
	 * @param run        要运行的任务
	 * @param unexpected 意外时执行的任务
	 */
	public static void runLoggedException(CatchRun run, Runnable unexpected) {
		try {
			run.run();
		} catch (Throwable e) {
			Log.err(e);
			unexpected.run();
		}
	}

	/**
	 * 对run封装，捕获报错并显示异常
	 * @param run 要运行的任务
	 * @return 封装后的Runnable
	 */
	public static Runnable runT0(Runnable run) {
		return () -> {
			try {
				run.run();
			} catch (Throwable e) {
				IntUI.showException(e);
			}
		};
	}

	/**
	 * 对run封装，捕获报错
	 * @param run 要运行的任务
	 * @return 封装后的Runnable
	 */
	public static Runnable runT(CatchRun run) {
		return runT("", run, null);
	}

	/**
	 * 对run封装，捕获报错
	 * @param run 要运行的任务
	 * @param el  用于定位
	 * @return 封装后的Runnable
	 */
	public static Runnable runT(CatchRun run, Element el) {
		return runT("", run, el);
	}

	/**
	 * 对run封装，捕获报错并显示异常信息
	 * @param text 异常信息
	 * @param run  要运行的任务
	 * @param el   用于定位
	 * @return 封装后的Runnable
	 */
	public static Runnable runT(String text, CatchRun run, Element el) {
		return () -> {
			try {
				run.run();
			} catch (Throwable th) {
				Log.err(th);
				if (!ModTools.loaded) return;
				Window window = IntUI.showException(text, th);
				if (el != null) window.setPosition(ElementUtils.getAbsolutePos(el));
			}
		};
	}

	/**
	 * 对Consumer封装，捕获报错
	 * @param cons Consumer
	 * @param <T>  类型
	 * @return 封装后的Consumer
	 */
	public static <T> Cons<T> consT(CCons<T> cons) {
		return consT(cons, null);
	}

	/**
	 * 对{@link Cons}封装，捕获报错并处理异常
	 * @param resolver 异常处理
	 * @return 封装后的cons
	 */
	public static <T> Cons<T> consT(CCons<T> cons, Cons<Throwable> resolver) {
		return t -> {
			try {
				cons.get(t);
			} catch (Throwable e) {
				if (resolver != null) resolver.get(e);
			}
		};
	}

	/**
	 * 对{@link Prov}封装，捕获报错
	 * @return 封装后的prov
	 */
	public static <T> Prov<T> provT(Prov<T> prov) {
		return () -> {
			try {
				return prov.get();
			} catch (Throwable e) {
				Log.err(e);
				return null;
			}
		};
	}

	/**
	 * 委托运行任务，根据条件停止运行
	 * @param r         任务
	 * @param stopBoolp 停止条件
	 * @return 封装后的Runnable
	 */
	public static Runnable delegate(Runnable r, Boolp stopBoolp) {
		var run = new Runnable() {
			Runnable r0;
			public void run() {
				r0.run();
			}
		};
		run.r0 = () -> {
			if (stopBoolp.get()) run.r0 = IntVars.EMPTY_RUN;
			r.run();
		};
		return run;
	}

	/**
	 * 设置日志处理器并执行任务
	 * @param logger 日志处理器
	 * @param prov   任务
	 * @return 任务结果
	 */
	public static <T> T setLogger(LogHandler logger, Prov<T> prov) {
		var prev = Log.logger;
		Log.logger = logger;
		try {
			return prov.get();
		} finally {
			Log.logger = prev;
		}
	}

	/**
	 * 遍历列表并执行{@code cons}
	 * @param list 列表
	 */
	public static <T> void each(List<T> list, Cons<T> cons) {
		for (int i = list.size(); i-- > 0; ) {
			cons.get(list.get(i));
		}
	}

	/**
	 * 抛出一个运行时异常
	 * @return 无返回值
	 */
	public static <T> T _throw() {
		throw new RuntimeException();
	}
	public static Object cast(Object object, Class<?> type) {
		if (object.getClass() == type) return object;
		if (object instanceof Number n && type.isPrimitive()) {
			if (type == byte.class) return n.byteValue();
			if (type == short.class) return n.shortValue();
			if (type == int.class) return n.intValue();
			if (type == long.class) return n.longValue();
			if (type == float.class) return n.floatValue();
			if (type == double.class) return n.doubleValue();
		}
		return object;
	}

	/** Run接口（带异常） */
	public interface CatchRun {
		void run() throws Throwable;
	}

	/** Boolp接口（带异常） */
	public interface CBoolp {
		boolean get() throws Throwable;
		@SuppressWarnings("Convert2Lambda")
		static Boolp of(String text, CBoolp boolp) {
			return new Boolp() {
				public boolean get() {
					try {
						return boolp.get();
					} catch (Throwable e) {
						IntUI.showException(text, e);
						Log.err(e);
						return false;
					}
				}
			};
		}
	}

	/** Boolc接口（带异常） */
	public interface CBoolc {
		void get(boolean b) throws Throwable;
		@SuppressWarnings("Convert2Lambda")
		static Boolc of(String text, CBoolc boolc) {
			return new Boolc() {
				public void get(boolean b) {
					try {
						boolc.get(b);
					} catch (Throwable e) {
						IntUI.showException(text, e);
						Log.err(e);
					}
				}
			};
		}
	}

	public static int compareVersions(String version1, String version2) {
		String[] levels1 = version1.split("\\.");
		String[] levels2 = version2.split("\\.");

		int length = Math.max(levels1.length, levels2.length);
		for (int i = 0; i < length; i++) {
			Integer v1         = i < levels1.length ? Integer.parseInt(levels1[i]) : 0;
			Integer v2         = i < levels2.length ? Integer.parseInt(levels2[i]) : 0;
			int     comparison = v1.compareTo(v2);
			if (comparison != 0) {
				return comparison;
			}
		}

		return 0;
	}

	/** Cons接口（带异常） */
	public interface CCons<T> {
		void get(T t) throws Throwable;
	}

	/** Prov接口（带异常） */
	public interface CProv<T> extends CProvT<T, Throwable> { }

	/** Prov接口（带异常） */
	public interface CProvT<T, E extends Throwable> {
		T get() throws E;
	}
}
