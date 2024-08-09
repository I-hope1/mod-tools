package modtools.utils.io;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.StrictMode;

import arc.Core;
import arc.files.Fi;
import arc.util.*;
import modtools.IntVars;
import modtools.ui.IntUI;
import modtools.utils.ArrayUtils;

public class FileUtils {

	public static Fi child(Fi parent, String newName, String... oldNames) {
		Fi child = parent.child(newName);
		for (String oldName : oldNames) {
			Fi child1 = parent.child(oldName);
			if (child1.exists() && child1.isDirectory()) child1.moveTo(child);
		}
		return child;
	}
	/** @return {@code true} if the file be deleted successfully */
	public static boolean delete(Fi fi) {
		return fi.exists() && (fi.isDirectory() ? fi.deleteDirectory() : fi.delete());
	}

	public static boolean openFile(Fi path) {
		return openFile(path.path());
	}

	private static      boolean init;
	public static boolean openFile(String path) {
		if (IntVars.isDesktop()) {
			return Core.app.openURI(path);
		}
		if (OS.isAndroid) {
			try {
				if (!init) {
					Reflect.invoke(StrictMode.class, "disableDeathOnFileUriExposure", ArrayUtils.EMPTY_ARRAY);

					init = true;
				}
				var    app    = (Activity) Core.app;
				Intent intent = new Intent(Intent.ACTION_VIEW);
				intent.setDataAndType(Uri.parse("file://" + path), "*/*");
				app.startActivity(intent);
			} catch (Throwable e) {
				IntUI.showException("Failed to open " + path, e);
				Log.err(e);
			}
			return true;
		}
		return Core.app.openFolder(path);
	}
}