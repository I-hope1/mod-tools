package modtools;

import arc.Core;
import arc.files.Fi;
import arc.func.*;
import arc.util.*;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.Bar;
import modtools.ui.IntUI;
import modtools.ui.comp.Window;
import modtools.ui.comp.Window.NoTopWindow;
import modtools.utils.Tools;

import java.io.*;

import static mindustry.Vars.*;
import static modtools.utils.Tools.runT;

public class Updater {
	private static boolean checkUpdates = true;

	private static boolean updateAvailable;
	private static String  updateUrl;
	private static String  updateBuild;

	/** asynchronously checks for updates. */
	public static void checkUpdate(Boolc done) {
		Http.get(STR."https://api.github.com/repos/\{IntVars.meta.repo}/releases/latest")
		 .error(e -> {
			 Log.err(e);
			 done.get(false);
		 })
		 .submit(res -> {
			 Jval val = Jval.read(res.getResultAsString());
			 // Log.info(val.toString(Jformat.formatted));
			 String newBuild = val.getString("tag_name", "0");
			 // Log.info("New: @, Old: @",newBuild, IntVars.meta.version);
			 if (Tools.compareVersions(newBuild, IntVars.meta.version) > 0) {
				 Jval   asset = val.get("assets").asArray().find(v -> v.getString("name", "").endsWith(".jar"));
				 String url   = asset.getString("browser_download_url", "");
				 Log.info(STR."Downloading mod-tools from: \{url}");
				 updateAvailable = true;
				 updateBuild = newBuild;
				 updateUrl = url;
				 Core.app.post(() -> {
					 showUpdateDialog();
					 done.get(true);
				 });
			 } else {
				 Core.app.post(() -> done.get(false));
			 }
		 });
	}
	public static void checkUpdate() {
		checkUpdate(hasUpdated -> {
			if (hasUpdated) showUpdateDialog();
		});
	}


	/** @return whether a new update is available */
	public static boolean isUpdateAvailable() {
		return updateAvailable;
	}

	/** shows the dialog for updating the game on desktop, or a prompt for doing so on the server */
	public static void showUpdateDialog() {
		if (!updateAvailable) return;

		if (!headless) {
			checkUpdates = false;
			IntUI.showCustomConfirm(STR."\{Core.bundle.format("mod-tools.update", "")} \{updateBuild}", "@mod-tools.update.confirm", "@ok", "@mod-tools.ignore", () -> {
				try {
					boolean[] cancel   = {false};
					float[]   progress = {0};
					int[]     length   = {0};

					Fi fileDest = IntVars.dataDirectory.child("versions");
					Fi file     = fileDest.child(STR."mod-tools.\{updateBuild}");

					Window dialog = new NoTopWindow("@mod-tools.updating");
					download(updateUrl, fileDest, i -> length[0] = i, v -> progress[0] = v,
					 () -> cancel[0], runT(() -> Vars.mods.importMod(file)),
					 e -> {
						 dialog.hide();
						 ui.showException(e);
					 });

					dialog.cont.add(new Bar(() -> length[0] == 0 ? Core.bundle.get("mod-tools.updating") : (int) (progress[0] * length[0]) / 1024 / 1024 + "/" + length[0] / 1024 / 1024 + " MB",
					 () -> Pal.accent, () -> progress[0])).width(400f).height(70f);
					dialog.buttons.button("@cancel", Icon.cancel, () -> {
						cancel[0] = true;
						dialog.hide();
					}).size(210f, 64f);
					dialog.setFillParent(false);
					dialog.show();
				} catch (Exception e) {
					ui.showException(e);
				}
			}, () -> checkUpdates = false);
		} else {
			checkUpdates = false;
		}
	}

	private static void download(String furl, Fi dest, Intc length, Floatc progressor, Boolp canceled, Runnable done,
	                             Cons<Throwable> error) {
		mainExecutor.submit(() -> Http.get(furl, (res) -> {
			BufferedInputStream in  = new BufferedInputStream(res.getResultAsStream());
			OutputStream        out = dest.write(false, 4096);

			byte[] data    = new byte[4096];
			long   size    = res.getContentLength();
			long   counter = 0;
			length.get((int) size);
			int x;
			while ((x = in.read(data, 0, data.length)) >= 0 && !canceled.get()) {
				counter += x;
				progressor.get((float) counter / (float) size);
				out.write(data, 0, x);
			}
			out.close();
			in.close();
			if (!canceled.get()) done.run();
		}, error));
	}
}
