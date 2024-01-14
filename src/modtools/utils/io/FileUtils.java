package modtools.utils.io;

import arc.files.Fi;
import arc.util.*;
import dalvik.system.BaseDexClassLoader;
import modtools.ModTools;

import java.io.File;
import java.net.*;

public class FileUtils {
	public static URI toURIOrFail(URL url) {
		try {
			return url.toURI();
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException(e);
		}
	}
	public static Fi findRoot() {
		try {
			URL url =  ModTools.class.getClassLoader().getResource("mod.hjson");
			return new Fi(new File(url.getPath().split("!")[0].substring(5)));
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
	public static Fi findRootAndroid() {;
		Object pathList = Reflect.get(BaseDexClassLoader.class, ModTools.class.getClassLoader(),
		 "pathList");
		Object[] arr = Reflect.get(pathList, "dexElements");
		return new Fi((File) Reflect.get(arr[0], "path"));
	}
}
