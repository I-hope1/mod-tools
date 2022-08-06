package modtools;

import arc.*;
import arc.struct.Seq;
import arc.util.Log;
import modtools.ui.Frag;
import modtools.ui.IntUI;
import modtools.ui.TopGroup;

import java.util.concurrent.CompletableFuture;

import static mindustry.Vars.ui;

public class IntVars {
	public static final String modName = "mod-tools";
	public static final Frag frag = new Frag();
	public static final TopGroup topGroup = new TopGroup();

	public static void load() {
		if (frag.getChildren().isEmpty()) frag.load();
		else topGroup.addChild(frag);
	}


	public static void showException(Exception e, boolean b) {
		if (b) {
			IntUI.showException(e);
		} else {
			Log.err(e);
		}
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
			callback.run();
			return 1;
		});
		try {
			completableFuture.get();
		} catch (Exception e) {
			showException(e, displayUI);
		}
	}

	public static final Seq<Runnable> resizeListenrs = new Seq<>();

	public static void addResizeListener(Runnable runnable) {
		resizeListenrs.add(runnable);
	}

	static {
		Core.app.addListener(new ApplicationListener() {
			@Override
			public void resize(int width, int height) {
				for (var r : resizeListenrs) r.run();
			}
		});

	}


}
