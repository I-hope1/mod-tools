package modtools;

import arc.Core;
import arc.files.Fi;
import arc.func.*;
import arc.scene.event.Touchable;
import arc.util.*;
import arc.util.serialization.Jval;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.Bar;
import modtools.ui.IntUI;
import modtools.ui.IntUI.ConfirmWindow;
import modtools.ui.comp.Window;
import modtools.ui.comp.Window.NoTopWindow;
import modtools.utils.Tools;
import modtools.utils.ui.FormatHelper;

import java.io.*;
import java.util.concurrent.atomic.*;

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
		 .error(e -> Core.app.post(() -> {
			 Log.err(e);
			 done.get(false);
		 }))
		 .submit(res -> Core.app.post(() -> {
			 Log.debug("Got response: @", res.getStatus());
			 Jval val = Jval.read(res.getResultAsString());
			 // Log.info(val.toString(Jformat.formatted));
			 String newBuild = val.getString("tag_name", "0");
			 if (Core.settings.getBool(IntVars.modName + "-update-ignore-" + newBuild, false)) {
				 done.get(false);
				 return;
			 }
			 // Log.info("New: @, Old: @",newBuild, IntVars.meta.version);
			 if (IntVars.meta.version != null && Tools.compareVersions(newBuild, IntVars.meta.version) > 0) {
				 Jval asset = val.get("assets").asArray().find(v -> v.getString("name", "").endsWith(".jar"));
				 if (asset == null) {
					 Log.err("No jar asset found in release: " + newBuild);
					 Core.app.post(() -> done.get(false));
					 return;
				 }
				 String url = asset.getString("browser_download_url", "");
				 Log.info(STR."Downloading mod-tools from: \{url}");
				 updateAvailable = true;
				 updateBuild = newBuild;
				 updateUrl = url;
				 Core.app.post(() -> {
					 showUpdateDialog();
					 done.get(true);
				 });
			 }
		 }));
	}
	public static void checkUpdate() {
		checkUpdate(_ -> { });
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
			ConfirmWindow window = IntUI.showCustomConfirm(STR."\{Core.bundle.format("mod-tools.update", "")} \{updateBuild}",
			 "@mod-tools.update.confirm",
			 "@ok", "@mod-tools.ignore",
			 () -> {
				 try {
					 AtomicBoolean cancel      = new AtomicBoolean(false);
					 AtomicInteger progress    = new AtomicInteger();
					 Floatp        getProgress = () -> Float.intBitsToFloat(progress.get());
					 Floatc        setProgress = p -> progress.set(Float.floatToIntBits(p));
					 AtomicInteger length      = new AtomicInteger();

					 Fi fileDir = IntVars.dataDirectory.child("versions");
					 Fi file    = fileDir.child(STR."mod-tools.\{updateBuild}.jar");

					 Window dialog = new NoTopWindow("@mod-tools.updating");
					 download(updateUrl, file, length::set, setProgress,
					  cancel::get, runT(() -> mods.importMod(file)),
						e -> Core.app.post(runT(() -> {
							dialog.hide();
							showException(e);
						})));

					 dialog.cont.add(new Bar(() -> {
						 if (length.get() == 0) {
							 return Core.bundle.get("mod-tools.updating");
						 }
						 return FormatHelper.fixed(getProgress.get() * length.get() / 1024f / 1024f, 2) + "/" + FormatHelper.fixed(length.get() / 1024f / 1024f, 2) + " MB";
					 },
						() -> Pal.accent, getProgress)).width(400f).height(70f);
					 dialog.buttons.button("@cancel", Icon.cancel, () -> {
						 cancel.set(true);
						 dialog.hide();
					 }).size(210f, 64f);
					 dialog.show();
				 } catch (Exception e) {
					 showException(e);
				 }
			 }, () -> checkUpdates = false);
			window.cont.row();
			// 不再提示此版本的更新
			String text = Core.bundle.format("mod-tools.update.ignore", updateBuild);
			window.cont.check(text, b -> Core.settings.put(IntVars.modName + "-update-ignore-" + updateBuild, b));
			window.shown(() -> window.hitter().touchable = Touchable.disabled);
		} else {
			checkUpdates = false;
		}
	}
	private static void showException(Throwable e) {
		Core.app.post(() -> ui.showException(e));
	}

	private static void download(String furl, Fi dest, Intc length, Floatc progressor, Boolp canceled, Runnable done,
	                             Cons<Throwable> error) {
		Http.get(furl, (res) -> {
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
			if (canceled.get()) {
				dest.delete();
			} else {
				done.run();
			}
		}, error);
	}
}
