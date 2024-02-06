package modtools;

import arc.Events;
import arc.files.Fi;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType.ResizeEvent;
import mindustry.mod.ModClassLoader;
import mindustry.mod.Mods.ModMeta;
import modtools.files.HFi;
import modtools.struct.MySet;
import modtools.struct.v6.AThreads;
import modtools.ui.IntUI;

import java.util.concurrent.*;

import static mindustry.Vars.ui;

public class IntVars {
	public static final String  modName = "mod-tools";
	public static       ModMeta meta;

	/** mod的根目录  */
	public static Fi root          = new HFi(IntVars.class.getClassLoader());
	public static Fi dataDirectory = Vars.dataDirectory.child("b0kkihope");

	public static final String         QQ         = "https://qm.qq.com/q/7rAZZaEMs&personal_qrcode_source=4";
	public static       ModClassLoader mainLoader = (ModClassLoader) Vars.mods.mainLoader();

	public static final String  NL = System.lineSeparator();
	public static       boolean hasDecompiler;

	public static void showException(Throwable e, boolean b)  {
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
	public static void async(String text, Runnable runnable, Runnable callback, boolean displayUI) {
		if (displayUI) ui.loadfrag.show(text);
		var completableFuture = CompletableFuture.runAsync(() -> {
			try {
				runnable.run();
				if (callback != null) callback.run();
			} catch (Throwable th) {
				showException(th, displayUI);
			}
			if (displayUI) ui.loadfrag.hide();
		});
		try {
			completableFuture.get();
		} catch (Throwable th) {
			showException(th, displayUI);
		}
	}

	public static final MySet<Runnable> resizeListeners = new MySet<>();
	public static void addResizeListener(Runnable runnable) {
		resizeListeners.add(runnable);
	}

	/** @return {@code true} if the file be deleted successfully */
	public static boolean delete(Fi fi) {
		return fi.exists() && (fi.isDirectory() ? fi.deleteDirectory() : fi.delete());
	}

	public interface Async {
		ExecutorService executor = AThreads.impl.boundedExecutor("hope-async", 1);
	}

	static {
		Events.on(ResizeEvent.class, __ -> {
			for (var r : resizeListeners) r.run();
		});
	}
}
