package modtools.utils.io;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.StrictMode;
import arc.Core;
import arc.Files.FileType;
import arc.files.Fi;
import arc.util.*;
import mindustry.Vars;
import modtools.IntVars;
import modtools.ui.IntUI;
import modtools.utils.ArrayUtils;

public class FileUtils {

	/**
	 * 将指定名称的子目录及其内容移动到新子目录下
	 * 如果新子目录不存在，它将被创建
	 * @param parent   原始父目录对象
	 * @param newName  新子目录的名称
	 * @param oldNames 一个或多个要移动的旧子目录的名称
	 * @return 返回新子目录对象，无论移动操作是否成功
	 */
	public static Fi child(Fi parent, String newName, String... oldNames) {
		// 创建或获取新子目录
		Fi child = parent.child(newName);

		// 遍历旧子目录名称数组
		for (String oldName : oldNames) {
			// 获取旧子目录对象
			Fi child1 = parent.child(oldName);

			// 如果旧子目录存在且为目录，则将其移动到新子目录下
			if (child1.exists() && child1.isDirectory()) child1.moveTo(child);
		}

		// 返回新子目录对象
		return child;
	}
	public static boolean isLocal(Fi fi) {
		return fi.type() == FileType.absolute || fi.type() == FileType.local || fi.type() == FileType.external;
	}
	public static Fi copyToTmp(Fi fi, Fi destDir, String newName) {
		// dest应该是本地文件
		if (!isLocal(destDir)) throw new IllegalArgumentException("destDir should be local file");
		if (isLocal(fi)) return fi;
		Fi toFi = destDir.child(newName == null ? fi.name() : newName);
		FileUtils.delete(toFi);
		fi.copyTo(toFi);
		return toFi;
	}
	public static Fi copyToTmp(Fi fi, String newName) {
		return copyToTmp(fi, Vars.tmpDirectory, newName);
	}
	public static Fi copyToTmp(Fi fi) {
		return copyToTmp(fi, null);
	}
	/** @return {@code true} if the file be deleted successfully */
	public static boolean delete(Fi fi) {
		return fi.exists() && (fi.isDirectory() ? fi.deleteDirectory() : fi.delete());
	}

	public static boolean openFile(Fi path) {
		return openFile(path.path());
	}

	private static boolean init;
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