package modtools;

import arc.Core;
import arc.files.Fi;
import arc.func.*;
import arc.util.*;
import arc.util.serialization.Jval;
import mindustry.core.Version;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.Bar;
import mindustry.ui.dialogs.BaseDialog;

import java.io.*;
import java.net.*;

import static mindustry.Vars.*;

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
			 Jval   val      = Jval.read(res.getResultAsString());
			 // Log.info(val.toString(Jformat.formatted));
			 String newBuild = val.getString("tag_name", "0");
			 // Log.info("New: @, Old: @",newBuild, IntVars.meta.version);
			 if (Runtime.Version.parse(newBuild)
						.compareTo(Runtime.Version.parse(IntVars.meta.version)) > 0) {
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


	/** @return whether a new update is available */
	public static boolean isUpdateAvailable() {
		return updateAvailable;
	}

	/** shows the dialog for updating the game on desktop, or a prompt for doing so on the server */
	public static void showUpdateDialog() {
		if (!updateAvailable) return;

		if (!headless) {
			checkUpdates = false;
			ui.showCustomConfirm(STR."\{Core.bundle.format("mod-tools.update", "")} \{updateBuild}", "@mod-tools.update.confirm", "@ok", "@mod-tools.ignore", () -> {
				try {
					boolean[] cancel   = {false};
					float[]   progress = {0};
					int[]     length   = {0};

					Fi fileDest = IntVars.dataDirectory.child("versions");
					Fi file     = fileDest.child(STR."mod-tools.\{updateBuild}");

					BaseDialog dialog = new BaseDialog("@mod-tools.updating");
					download(updateUrl, fileDest, i -> length[0] = i, v -> progress[0] = v, () -> cancel[0], () -> {
						try {
							Runtime.getRuntime().exec(OS.isMac ?
							 new String[]{javaPath, "-XstartOnFirstThread", "-DlastBuild=" + Version.build, "-Dberestart", "-Dbecopy=" + fileDest.absolutePath(), "-jar", file.absolutePath()} :
							 new String[]{javaPath, "-DlastBuild=" + Version.build, "-Dberestart", "-Dbecopy=" + fileDest.absolutePath(), "-jar", file.absolutePath()}
							);
							System.exit(0);
						} catch (IOException e) {
							ui.showException(e);
						}
					}, e -> {
						dialog.hide();
						ui.showException(e);
					});

					dialog.cont.add(new Bar(() -> length[0] == 0 ? Core.bundle.get("be.updating") : (int) (progress[0] * length[0]) / 1024 / 1024 + "/" + length[0] / 1024 / 1024 + " MB", () -> Pal.accent, () -> progress[0])).width(400f).height(70f);
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
		mainExecutor.submit(() -> {
			try {
				HttpURLConnection   con = (HttpURLConnection) new URL(furl).openConnection();
				BufferedInputStream in  = new BufferedInputStream(con.getInputStream());
				OutputStream        out = dest.write(false, 4096);

				byte[] data    = new byte[4096];
				long   size    = con.getContentLength();
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
			} catch (Throwable e) {
				error.get(e);
			}
		});
	}
}
