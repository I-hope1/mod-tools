package modtools.utils;

import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import modtools_lib.MyReflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.concurrent.ThreadPoolExecutor;

import static modtools_lib.MyReflect.unsafe;

public class CatchError {
	static Handler exceptionHandler = new Handler();

	public static void main(ThreadPoolExecutor main) {
		try {
			uncaughtF = Thread.class.getDeclaredField("uncaughtExceptionHandler");
			MyReflect.setOverride(uncaughtF);
			main.setThreadFactory(r -> newThread(r, "Main Executor", true));

			Field field = Thread.class.getDeclaredField("defaultUncaughtExceptionHandler");
			MyReflect.setOverride(field);
			field.set(null, exceptionHandler);

			/*field = ThreadPoolExecutor.class.getDeclaredField("defaultHandler");
			MyReflect.setOverride(field);
			MyReflect.lookupRemoveFinal(field);
			field.set(null, handler);
			field = ThreadPoolExecutor.class.getDeclaredField("handler");
			MyReflect.setOverride(field);
			field.set(main, handler);

			setWorker();*/
			new MyThreadGroup(Thread.currentThread(), "maxkmax");
			setThreads();
		} catch (Throwable e) {
			Log.err(e);
		}
	}

	public static Field uncaughtF;

	public static void setWorker() throws Throwable {
		Field f = ThreadPoolExecutor.class.getDeclaredField("workers");
		long offset = unsafe.objectFieldOffset(f);
		HashSet workers = (HashSet) unsafe.getObject(Vars.mainExecutor, offset);
		Field[] fields = MyReflect.lookupGetFields(getWorker());
		Field thread = null;
		for (Field fi : fields) {
			if (fi.getName().equals("thread")) thread = fi;
		}
		long toffset = unsafe.objectFieldOffset(thread);
//		Log.info(workers);
		workers.forEach(w -> {
//				Log.info("bef:" + unsafe.getObject(o, uncaughtOf));F
			try {
				setExceptionHandler((Thread) unsafe.getObject(w, toffset));
			} catch (Exception e) {
				Log.err(e);
			}
//				Log.info("now:" + unsafe.getObject(o, uncaughtOf));
		});
//		Log.info(Vars.mainExecutor);
	}

	public static Class<?> getWorker() {
		Class<?>[] classes = ThreadPoolExecutor.class.getDeclaredClasses();
		for (Class<?> cls : classes) {
			if (cls.getSimpleName().equals("Worker")) return cls;
		}
		return null;
	}

	public static void setExceptionHandler(Thread thread) throws Exception {
		if (thread == null) return;
//		Log.info("setHandler:" + thread);
//		Log.info("bef:" + uncaughtF.get(thread));
		uncaughtF.set(thread, exceptionHandler);
//		thread.setUncaughtExceptionHandler(exceptionHandler);
	}

	public static Thread newThread(Runnable r, String name, boolean daemon) {
		Thread thread = name == null ? new Thread(r) : new Thread(r, name);
//		thread.setDaemon(daemon);
		try {
			setExceptionHandler(thread);
		} catch (Exception e) {
			Log.err(e);
		}
		return thread;
	}

	static Method m;

	static {
		try {
			m = Thread.class.getDeclaredMethod("getThreads");
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
		MyReflect.setOverride(m);
	}

	public static void setThreads() throws Exception {
		Thread[] threads = (Thread[]) m.invoke(null);
		for (Thread t : threads) {
			if (t != null) {
				setExceptionHandler(t);
//					add.invoke(this, t);
			}
		}
	}

	public static class MyThreadGroup extends ThreadGroup {

		public MyThreadGroup(Thread thread, String name) throws Exception {
			super(name);
			ThreadGroup parent = getParent();
			Field f = ThreadGroup.class.getDeclaredField("parent");
			MyReflect.setOverride(f);
			f.set(this, null);
			ThreadGroup child = thread.getThreadGroup().getParent();
			f.set(child, this);
			ThreadGroup[] groups = (ThreadGroup[]) groupsF.get(parent);
			for (int i = 0, len = groups.length; i < len; i++) {
				if (groups[i] == this) groups[i] = null;
			}
			/*groups = (ThreadGroup[]) groupsF.get(child);
			for (ThreadGroup g : groups) {
				if (g == null) continue;
				Thread[] threads = (Thread[]) tf.get(g);
				for (Thread t : threads) {
					setExceptionHandler(t);
				}
			}*/
		}

		static Field tf, groupsF;
		static Method add;

		static {
			try {
				add = ThreadGroup.class.getDeclaredMethod("add", Thread.class);
				tf = ThreadGroup.class.getDeclaredField("threads");
				groupsF = ThreadGroup.class.getDeclaredField("groups");
			} catch (NoSuchFieldException | NoSuchMethodException e) {
				throw new RuntimeException(e);
			}
			MyReflect.setOverride(add);
			MyReflect.setOverride(tf);
			MyReflect.setOverride(groupsF);
		}

		boolean disabled = false;

		@Override
		public void uncaughtException(Thread t, Throwable e) {
			Time.runTask(4, () -> disabled = false);
			if (!disabled) {
				Vars.ui.showException("发生了异常", e);
			}
			disabled = true;
//			super.uncaughtException(t, e);
		}
	}

	static class Handler implements Thread.UncaughtExceptionHandler {

		public void uncaughtException(Thread t, Throwable e) {
			Log.info("Unhandled exception caught!");
		}
	}
}
