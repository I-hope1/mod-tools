package modtools.utils.io;

import arc.files.Fi;
import arc.util.*;
import dalvik.system.BaseDexClassLoader;
import modtools.ModTools;

import java.io.File;
import java.net.*;
import java.util.Objects;

public class FileUtils {
	public static Fi findRoot() {
		try {
			URL    url  = ModTools.class.getClassLoader().getResource("mod.hjson");
			String path = Objects.requireNonNull(url).toURI().toString();
			path = path.substring(path.indexOf("file:") + 5, path.lastIndexOf('!'));
			// Log.info(path);
			return new Fi(new File(path));
		} catch (Throwable ignored) {}

		if (OS.isWindows || OS.isMac) {
			URL url = ((URLClassLoader) ModTools.class.getClassLoader()).getURLs()[0];
			try {
				return Fi.get(url.toURI().getPath());
			} catch (URISyntaxException e) {
				throw new RuntimeException(e);
			}
		}
		if (OS.isAndroid) return findRootAndroid();
		throw new UnsupportedOperationException();
	}
	public static Fi findRootAndroid() {
		Object pathList = Reflect.get(BaseDexClassLoader.class, ModTools.class.getClassLoader(),
		 "pathList");
		Object[] arr = Reflect.get(pathList, "dexElements");
		return new Fi((File) Reflect.get(arr[0], "path"));
	}
}
