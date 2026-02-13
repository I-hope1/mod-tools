package modtools;

import arc.*;
import arc.files.Fi;
import arc.func.Cons;
import arc.math.geom.Vec2;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.Vars;
import mindustry.game.EventType.ResizeEvent;
import mindustry.mod.*;
import mindustry.mod.Mods.*;
import modtools.struct.*;
import modtools.struct.v6.AThreads;
import modtools.ui.IntUI;
import modtools.utils.Tools;
import modtools.utils.files.HFi;
import modtools.utils.io.FileUtils;

import java.util.concurrent.*;

import static mindustry.Vars.ui;

public class IntVars {
	public static final String   modName     = "mod-tools";
	public static final MouseVec mouseVec    = new MouseVec();
	public static final Vec2     mouseWorld  = new Vec2();
	public static final int      javaVersion = getVersion();

	private static int getVersion() {
		String version = System.getProperty("java.version");
		// Simpler version parsing
		if (version.startsWith("1.")) {
			version = version.substring(2, 3);
		} else {
			int dot = version.indexOf(".");
			if (dot != -1) { version = version.substring(0, dot); }
		}
		try {
			return Integer.parseInt(version);
		} catch (NumberFormatException e) {
			Log.err("Failed to parse Java version: " + System.getProperty("java.version"), e);
			return 8; // Default to a reasonable fallback
		}
	}

	public static final Runnable EMPTY_RUN = () -> { };
	/** @see Mods#metas */
	public static       ModMeta  meta;

	/** mod的根目录 */
	public static LoadedMod mod;
	public static Fi        root          = new HFi(IntVars.class.getClassLoader());
	public static Fi        dataDirectory = FileUtils.child(Vars.dataDirectory, modName.replace('-', '_'), "b0kkihope");

	public static final String         QQ         = "https://qm.qq.com/q/7rAZZaEMs&personal_qrcode_source=4";
	public static       ModClassLoader mainLoader = (ModClassLoader) Vars.mods.mainLoader();

	public static final String  NL = System.lineSeparator();
	public static       boolean hasDecompiler;

	public static Json json = new Json() {
		@SuppressWarnings("unchecked")
		@Override
		public <T> T readValue(Class<T> type, Class elementType, JsonValue jsonData, Class keytype) {
			if (type == Class.class) {
				try {
					return (T) Vars.mods.mainLoader().loadClass(jsonData.asString());
				} catch (ClassNotFoundException e) {
					throw new RuntimeException(e);
				}
			}
			return super.readValue(type, elementType, jsonData, keytype);
		}
	};

	/** Shows an exception either in the UI or logs it. */
	public static void showException(Throwable e, boolean b) {
		if (b) {
			IntUI.showException(e);
		} else {
			Log.err(e);
		}
	}

	public static void async(Runnable runnable) {
		async(runnable, null);
	}

	public static void async(Runnable runnable, Runnable callback) {
		async(null, runnable, callback, false);
	}
	public static void async(String text, Runnable runnable, Runnable callback) {
		async(text, runnable, callback, ui != null);
	}
	/**
	 * Runs a task asynchronously.
	 * @param text      Text for the loading fragment (optional).
	 * @param runnable  The task to run asynchronously.
	 * @param callback  Task to run on the main thread after completion (optional).
	 * @param displayUI Whether to show a loading fragment and display errors in the UI.
	 */
	public static void async(String text, Runnable runnable, Runnable callback, boolean displayUI) {
		if (displayUI) ui.loadfrag.show(text);
		CompletableFuture.runAsync(() -> {
			runnable.run();
			if (callback != null) callback.run();
		}).exceptionally((th) -> {
			showException(th, displayUI);
			return null;
		}).getNow(null);
	}

	public static final MySet<Runnable> resizeListeners = new MySet<>();
	/**
	 * 添加窗口大小调整监听器
	 * @param runnable 当窗口大小发生改变时要执行的任务
	 */
	public static void addResizeListener(Runnable runnable) {
		resizeListeners.add(runnable);
	}


	/** 提交到主线程运行 */
	public static void postToMain(Runnable run) {
		if (Thread.currentThread().getContextClassLoader() == Vars.class.getClassLoader()) {
			run.run();
		} else { Core.app.post(Tools.runT0(run)); }
	}
	/** Checks if the current OS is considered Desktop (Windows or Mac). */
	public static boolean isDesktop() {
		return OS.isWindows || OS.isMac || OS.isLinux ;
	}

	public static LazyValue<ExecutorService> EXECUTOR = LazyValue.of(() -> AThreads.impl.boundedExecutor("hope-async", 1));

	public static void dispose() {
		resizeListeners.clear();
		Events.remove(ResizeEvent.class, resizeEventCons);
		EXECUTOR.get().shutdownNow();
	}

	public static final Cons<ResizeEvent> resizeEventCons = _ -> {
		for (var r : resizeListeners) r.run();
	};

	static {
		Events.on(ResizeEvent.class, resizeEventCons);
	}

	public static void load() {
		if (Core.bundle.has("mod-tools.description")) { meta.description = Core.bundle.get("mod-tools.description"); }
	}
	/** A Vec2 representing the mouse position, updated periodically. */
	public static class MouseVec extends Vec2 {
		static {
			init();
		}
		static void init(){
			Tools.TASKS.add(() -> {
				if (Vars.state.isGame()) {
					mouseWorld.set(Core.camera.unproject(mouseVec.x, mouseVec.y));
				}
			});
		}

		public void require() {
			super.set(Core.input.mouse());
		}
		/* 禁止外部设置 */
		public Vec2 set(Vec2 v) {
			Log.err("Attempted to set MouseVec directly.", new Throwable()); // Log attempt
			return this;
		}
	}
}
