package modtools.utils;

import arc.*;
import arc.files.Fi;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.struct.Seq;
import arc.util.Timer;
import arc.util.*;
import arc.util.Timer.Task;
import dalvik.system.DexFile;
import hope_android.FieldUtils;
import mindustry.game.EventType.Trigger;
import modtools.ui.IntUI;
import modtools.ui.components.Window;
import modtools.ui.effect.ScreenSampler;
import rhino.ScriptRuntime;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
import java.util.jar.*;
import java.util.regex.Pattern;

import static ihope_lib.MyReflect.unsafe;
import static mindustry.Vars.*;

public class Tools {
	public static TaskSet tasks = new TaskSet();

	static {
		Events.run(Trigger.update, () -> {
			tasks.exec();
		});
	}

	public static boolean validPosInt(String text) {
		return text.matches("^\\d+(\\.\\d*)?([Ee]\\d+)?$");
	}

	public static Pattern compileRegExpCatch(String text) {
		try {
			return compileRegExp(text);
		} catch (Throwable e) {
			return null;
		}
	}
	public static Pattern compileRegExp(String text) {
		return text == null || text.isEmpty() ? null : Pattern.compile(text, Pattern.CASE_INSENSITIVE);
	}

	public static boolean isNum(String text) {
		try {
			return !ScriptRuntime.isNaN(ScriptRuntime.toNumber(text));
		} catch (Throwable ignored) {
			return false;
		}
	}
	public static float asFloat(String text) {
		try {
			return Float.parseFloat(text);
		} catch (Throwable e) {
			return Float.NaN;
		}
	}
	public static int asInt(String text) {
		float f = asFloat(text);
		return Float.isNaN(f) ? 0 : (int) f;
	}


	public static String clName(Object o) {
		return o.getClass().getName();
	}

	// 去除颜色
	public static String format(String s) {
		return s.replaceAll("\\[(\\w+?)\\]", "[\u0001$1]");
	}

	public static Vec2 getAbsPosCenter(Element el) {
		return el.localToStageCoordinates(Tmp.v1.set(el.getWidth() / -2f, el.getHeight() / -2f));
	}
	public static final Vec2 v1 = new Vec2(), v2 = new Vec2();
	public static Vec2 getAbsPos(Element el) {
		if (true) return el.localToStageCoordinates(v1.set(0, 0));
		Vec2 vec2 = Tmp.v1.set(el.x, el.y);
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
					copyValue(f, from, to);
				}
			}
			cls = cls.getSuperclass();
		}
	}
	public static void copyValue(Field f, Object from, Object to) {
		Class<?> type   = f.getType();
		long     offset = unsafe.objectFieldOffset(f);
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
		}, 1f, 1f, -1);
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


	public static Set<Class<?>> getClasses(String pack) {
		return OS.isAndroid ? getClasses1(pack) : getClasses0(pack);
	}

	public static Set<Class<?>> getClasses1(String pack) {
		if (true) return new LinkedHashSet<>();
		Set<Class<?>> classNameList = new LinkedHashSet<>();
		try {
			DexFile             df          = new DexFile(new Fi(".").file());//通过DexFile查找当前的APK中可执行文件
			Enumeration<String> enumeration = df.entries();//获取df中的元素  这里包含了所有可执行的类名 该类名包含了包名+类名的方式
			while (enumeration.hasMoreElements()) {//遍历
				String className = enumeration.nextElement();

				if (className.startsWith(pack)) {//在当前所有可执行的类里面查找包含有该包名的所有类
					classNameList.add(mods.mainLoader().loadClass(className));
				}
			}
		} catch (IOException | ClassNotFoundException e) {
			Log.err(e);
		}
		return classNameList;
	}
	public static Set<Class<?>> getClasses0(String pack) {
		// 第一个class类的集合
		Set<Class<?>> classes = new LinkedHashSet<Class<?>>();
		// 是否循环迭代
		boolean recursive = true;
		// 获取包的名字 并进行替换
		String packageDirName = pack.replace('.', '/');
		// 定义一个枚举的集合 并进行循环来处理这个目录下的things
		Enumeration<URL> dirs;
		try {
			dirs = mods.mainLoader().getResources(packageDirName);
			// 循环迭代下去
			while (dirs.hasMoreElements()) {
				// 获取下一个元素
				URL url = dirs.nextElement();
				// 得到协议的名称
				String protocol = url.getProtocol();
				// 如果是以文件的形式保存在服务器上
				if ("file".equals(protocol)) {
					// 获取包的物理路径
					String filePath = URLDecoder.decode(url.getFile(), StandardCharsets.UTF_8);
					// 以文件的方式扫描整个包下的文件 并添加到集合中
					findClassesInPackageByFile(pack, filePath, recursive, classes);
				} else if ("jar".equals(protocol)) {
					// 如果是jar包文件
					// 定义一个JarFile
					System.out.println("jar类型的扫描");
					JarFile jar;
					try {
						// 获取jar
						jar = ((JarURLConnection) url.openConnection()).getJarFile();
						// 从此jar包 得到一个枚举类
						Enumeration<JarEntry> entries = jar.entries();
						findClassesInPackageByJar(pack, entries, packageDirName, recursive, classes);
					} catch (IOException e) {
						// log.error("在扫描用户定义视图时从jar包获取文件出错");
						e.printStackTrace();
					}
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		return classes;
	}
	/* 以文件的形式来获取包下的所有Class
	 *
	 * @param packageName
	 * @param packagePath
	 * @param recursive
	 * @param classes
	 */
	private static void findClassesInPackageByFile(String packageName, String packagePath, final boolean recursive,
																								 Set<Class<?>> classes) {
		// 获取此包的目录 建立一个File
		File dir = new File(packagePath);
		// 如果不存在或者 也不是目录就直接返回
		if (!dir.exists() || !dir.isDirectory()) {
			// log.warn("用户定义包名 " + packageName + " 下没有任何文件");
			return;
		}
		// 如果存在 就获取包下的所有文件 包括目录
		// 自定义过滤规则 如果可以循环(包含子目录) 或则是以.class结尾的文件(编译好的java类文件)
		File[] dirfiles = dir.listFiles(file ->
		 (recursive && file.isDirectory()) || file.getName().endsWith(".class"));
		// 循环所有文件
		assert dirfiles != null;
		for (File file : dirfiles) {
			// 如果是目录 则继续扫描
			if (file.isDirectory()) {
				findClassesInPackageByFile(packageName + "." + file.getName(), file.getAbsolutePath(), recursive, classes);
			} else {
				// 如果是java类文件 去掉后面的.class 只留下类名
				String className = file.getName().substring(0, file.getName().length() - 6);
				try {
					// 添加到集合中去
					// classes.add(Class.forName(packageName + '.' +
					// className));
					// 经过回复同学的提醒，这里用forName有一些不好，会触发static方法，没有使用classLoader的load干净
					classes.add(Thread.currentThread().getContextClassLoader().loadClass(packageName + '.' + className));
				} catch (ClassNotFoundException e) {
					// log.error("添加用户自定义视图类错误 找不到此类的.class文件");
					e.printStackTrace();
				}
			}
		}
	}

	/**
	 * 以jar的形式来获取包下的所有Class
	 *
	 * @param packageName    包名
	 * @param entries        ？？？
	 * @param packageDirName ？？？
	 * @param recursive      ？？？
	 * @param classes        ？？？
	 */
	private static void findClassesInPackageByJar(String packageName, Enumeration<JarEntry> entries,
																								String packageDirName, final boolean recursive,
																								Set<Class<?>> classes) {
		// 同样的进行循环迭代
		while (entries.hasMoreElements()) {
			// 获取jar里的一个实体 可以是目录 和一些jar包里的其他文件 如META-INF等文件
			JarEntry entry = entries.nextElement();
			String   name  = entry.getName();
			// 如果是以/开头的
			if (name.charAt(0) == '/') {
				// 获取后面的字符串
				name = name.substring(1);
			}
			// 如果前半部分和定义的包名相同
			if (name.startsWith(packageDirName)) {
				int idx = name.lastIndexOf('/');
				// 如果以"/"结尾 是一个包
				if (idx != -1) {
					// 获取包名 把"/"替换成"."
					packageName = name.substring(0, idx).replace('/', '.');
				}
				// 如果可以迭代下去 并且是一个包
				if ((idx != -1) || recursive) {
					// 如果是一个.class文件 而且不是目录
					if (name.endsWith(".class") && !entry.isDirectory()) {
						// 去掉后面的".class" 获取真正的类名
						String className = name.substring(packageName.length() + 1, name.length() - 6);
						try {
							// 添加到classes
							classes.add(Class.forName(packageName + '.' + className));
						} catch (ClassNotFoundException e) {
							// .error("添加用户自定义视图类错误 找不到此类的.class文件");
							e.printStackTrace();
						}
					}
				}
			}
		}
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
		//noinspection unchecked
		return (T) o;
	}
	public static long fieldOffset(boolean isStatic, Field f) {
		return OS.isAndroid ? FieldUtils.getFieldOffset(f) : isStatic ? unsafe.staticFieldOffset(f) : unsafe.objectFieldOffset(f);
	}
	public static Object getFieldValue(Object o, long off, Class<?> type) {
		if (int.class.equals(type)) {
			return unsafe.getInt(o, off);
		} else if (float.class.equals(type)) {
			return unsafe.getFloat(o, off);
		} else if (double.class.equals(type)) {
			return unsafe.getDouble(o, off);
		} else if (long.class.equals(type)) {
			return unsafe.getLong(o, off);
		} else if (char.class.equals(type)) {
			return unsafe.getChar(o, off);
		} else if (byte.class.equals(type)) {
			return unsafe.getByte(o, off);
		} else if (short.class.equals(type)) {
			return unsafe.getShort(o, off);
		} else if (boolean.class.equals(type)) {
			return unsafe.getBoolean(o, off);
		} else {
			return unsafe.getObject(o, off);
		}
	}
	public static void setFieldValue(Field f, Object obj, Object value) {
		Class<?> type     = f.getType();
		boolean  isStatic = Modifier.isStatic(f.getModifiers());
		Object   o        = isStatic ? f.getDeclaringClass() : obj;
		long     offset   = fieldOffset(isStatic, f);
		setFieldValue(o, offset, value, type);
	}
	public static void setFieldValue(Object o, long off, Object value, Class<?> type) {
		if (int.class.equals(type)) {
			unsafe.putInt(o, off, ((Number) value).intValue());
		} else if (float.class.equals(type)) {
			unsafe.putFloat(o, off, ((Number) value).floatValue());
		} else if (double.class.equals(type)) {
			unsafe.putDouble(o, off, ((Number) value).doubleValue());
		} else if (long.class.equals(type)) {
			unsafe.putLong(o, off, ((Number) value).longValue());
		} else if (char.class.equals(type)) {
			unsafe.putChar(o, off, (char) value);
		} else if (byte.class.equals(type)) {
			unsafe.putByte(o, off, ((Number) value).byteValue());
		} else if (short.class.equals(type)) {
			unsafe.putShort(o, off, ((Number) value).shortValue());
		} else if (boolean.class.equals(type)) {
			unsafe.putBoolean(o, off, (boolean) value);
		} else {
			unsafe.putObject(o, off, value);
			/*if (f.getType().isArray()) {
				o = Arrays.copyOf((Object[]) o, Array.getLength(o));
			}*/
		}
	}

	public static <T> T or(T t1, T t2) {
		return t1 == null ? t2 : t1;
	}
	public static <T> T or(T t1, Prov<T> t2) {
		return t1 == null ? t2.get() : t1;
	}
	/** @return 是否不相等，相等就会设置值 */
	public static boolean EQSET(long[] arr, long t) {
		if (arr[0] != t) {
			arr[0] = t;
			return true;
		}
		return false;
	}
	private static SR sr_instance = new SR<>(null);
	public static <T> SR<T> sr(T value) {
		return sr_instance.setv(value);
	}

	public static boolean test(Pattern pattern, String text) {
		return pattern == null || pattern.matcher(text).find();
	}
	public static void __(Object __) {}
	public static <T> void checknull(T t, Consumer<T> cons) {
		if (t != null) cons.accept(t);
	}
	/**
	 * 检查是否为null，
	 *
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

	public static void quietScreenshot(Element element) {
		// ui.update();
		ScreenSampler.pause();
		JSFunc.dialog(screenshot(element, true, (region, pixmap) -> {
			Fi fi = screenshotDirectory.child(
			 Optional.ofNullable(element.name)
				.orElseGet(() -> "" + Time.nanos()) + ".png");
			// PixmapIO.writePng(fi, pixmap);
			// pixmap.dispose();

			Core.app.post(() -> ui.showInfoFade(Core.bundle.format("screenshot", fi.path())));
		}));
		ScreenSampler.contiune();
		// Time.runTask(30, w::hide);
	}
	public static TextureRegion screenshot(Element element, Cons2<TextureRegion, Pixmap> callback) {
		return screenshot(element, false, callback);
	}
	/** 使用ScreenUtils截图 */
	public static TextureRegion screenshot(Element el, boolean clear, Cons2<TextureRegion, Pixmap> callback) {
		int w = (int) el.getWidth(),
		 h = (int) el.getHeight();

		// Draw.shader();
		// 清空
		if (clear) {
			clearScreen();
			el.draw();
			Draw.flush();
		}
		Vec2   vec2   = getAbsPos(el);
		Pixmap pixmap = ScreenUtils.getFrameBufferPixmap((int) vec2.x, (int) vec2.y, w, h, true);

		TextureRegion textureRegion = new TextureRegion(new Texture(pixmap), 0, 0, w, h);
		if (callback != null) callback.get(textureRegion, pixmap);
		/* Core.scene.draw();
		Draw.flush(); */
		return textureRegion;
	}
	public static void clearScreen() {
		Gl.clearColor(0, 0, 0, 0);
		Gl.clear(Gl.colorBufferBit | Gl.depthBufferBit);
	}

	/**
	 * 返回List的第i个元素，超过区间返回null
	 * 负数index为倒数第i个元素
	 *
	 * @param list 列表
	 * @param i    索引
	 */
	public static <T> T getNull(List<T> list, int i) {
		if (i < 0) i += list.size();
		return i < list.size() ? list.get(i) : null;
	}


	/** 自动监控原seq */
	public static <T> Seq<T> selectUpdateFrom(Seq<T> items, Boolf<T> predicate) {
		Seq<T> arr  = new Seq<>();
		int[]  size = {items.size};
		tasks.add(() -> {
			if (items.size != size[0]) arr.selectFrom(items, predicate);
		});
		return arr.selectFrom(items, predicate);
	}

	public static class SR<T> {
		private T value;

		public SR(T value) {
			this.value = value;
		}

		public SR<T> setv(T value) {
			this.value = value;
			return this;
		}
		public SR<T> set(Function<T, T> func) {
			if (func != null) value = func.apply(value);
			return this;
		}
		public SR<T> setnull(Boolf<T> condition) {
			return set(condition, null);
		}

		public SR<T> set(Boolf<T> conditon, T newValue) {
			if (conditon != null && conditon.get(value)) value = newValue;
			return this;
		}

		public SR<T> ifPresent(Consumer<T> cons) {
			return ifPresent(cons, null);
		}

		public SR<T> ifPresent(Consumer<T> cons, Runnable nullrun) {
			if (value != null) cons.accept(value);
			else if (nullrun != null) nullrun.run();
			return this;
		}

		/**
		 * @param cons 如果满足就执行
		 *
		 * @throws RuntimeException 当执行后抛出
		 */
		public <R> SR<T> isInstance(Class<R> cls, Consumer<R> cons) throws SatisfyException {
			if (cls.isInstance(value)) {
				cons.accept(cls.cast(value));
				throw new SatisfyException();
			}
			return this;
		}

		public SR<T> cons(float f, BiConsumer<T, Float> cons) {
			cons.accept(value, f);
			return this;
		}
		public SR<T> cons(int i, BiConsumer<T, Integer> cons) {
			cons.accept(value, i);
			return this;
		}
		public <R> SR<T> cons(R obj, BiConsumer<T, R> cons) {
			cons.accept(value, obj);
			return this;
		}

		public SR<T> cons(Consumer<T> cons) {
			cons.accept(value);
			return this;
		}
		public SR<T> ifRun(boolean b, Consumer<T> cons) {
			if (b) cons.accept(value);
			return this;
		}

		public boolean test(Predicate<T> predicate) {
			return value != null && predicate.test(value);
		}
		public T get() {
			return value;
		}
		public <R> R get(Function<T, R> func) {
			return func.apply(value);
		}

		/* ---- for classes ---- */
		/**
		 * 判断是否继承
		 *
		 * @param cons 形参为满足的<code>class</code>
		 */
		public SR<T> isExtend(Consumer<Class<?>> cons, Class<?>... classes) {
			if (!(value instanceof Class<?> orgin)) throw new IllegalStateException("Value isn't a class");

			for (Class<?> cl : classes) {
				if (cl.isAssignableFrom(orgin)) {
					cons.accept(cl);
					break;
				}
			}
			return this;
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
				run.run0();
			} catch (Throwable th) {
				Window window = IntUI.showException(text, th);
				if (el != null) window.setPosition(getAbsPos(el));
			}
		};
	}

	/** 只是标识 */
	public static class SatisfyException extends Exception {}

	public interface CatchRun {
		void run0() throws Throwable;
		/* default void run() {
			try {
				run0();
			} catch (Throwable ignored) {}
		} */
	}

	// Reflection
	public static Object invoke(Method m, Object obj, Object... args) {
		try {
			return m.invoke(obj, args);
		} catch (IllegalAccessException | InvocationTargetException e) {
			throw new RuntimeException(e);
		}
	}
}

