package modtools;

import arc.Events;
import arc.util.Log;
import mindustry.game.EventType.ResizeEvent;
import modtools.ui.IntUI;
import modtools.utils.MySet;

import java.util.concurrent.CompletableFuture;

import static mindustry.Vars.ui;

public class IntVars {
	public static final String  modName = "mod-tools";
	public static final String  NL      = System.lineSeparator();
	public static       boolean hasDecomplier;


	public static void showException(Exception e, boolean b) {
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
		CompletableFuture<?> completableFuture = CompletableFuture.supplyAsync(() -> {
			try {
				runnable.run();
			} catch (Exception err) {
				showException(err, displayUI);
			}
			if (displayUI) ui.loadfrag.hide();
			if (callback != null) callback.run();
			return 1;
		});
		try {
			completableFuture.get();
		} catch (Exception e) {
			showException(e, displayUI);
		}
	}

	public static final MySet<Runnable> resizeListeners = new MySet<>();
	public static void addResizeListener(Runnable runnable) {
		resizeListeners.add(runnable);
	}

	static {
		// new Not("aaa");
		Events.on(ResizeEvent.class, e -> {
			for (var r : resizeListeners) r.run();
		});
	}
}
