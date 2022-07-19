package modtools;

import arc.Core;
import arc.util.Log;
import modtools.ui.Frag;

import java.util.concurrent.CompletableFuture;

import static mindustry.Vars.ui;

public class IntVars {
	public static final String modName = "mod-tools";
	public static final Frag frag = new Frag();

	public static void load() {
		if (frag.getChildren().isEmpty()) frag.load();
		else Core.scene.add(frag);
	}


	public static void showException(Exception e, boolean b) {
		if (b) {
			ui.showException(e);
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


}
