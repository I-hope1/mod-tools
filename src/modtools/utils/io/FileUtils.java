package modtools.utils.io;

import android.content.Intent;
import android.net.Uri;
import arc.Core;
import arc.backend.android.AndroidApplication;
import arc.files.Fi;
import arc.util.OS;
import modtools.IntVars;

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

	public static boolean openFile(String path) {
		if (IntVars.isDesktop()) {
			return Core.app.openURI(path);
		}
		if (OS.isAndroid) {
			Uri    uri    = Uri.parse(path);
			Intent intent = new Intent(Intent.ACTION_PICK);
			intent.setDataAndType(uri, "*/*");
			((AndroidApplication) Core.app).startActivity(intent);
		}
		return Core.app.openFolder(path);
	}
}