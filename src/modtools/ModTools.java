package modtools;

import arc.*;
import arc.files.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.PropertiesUtils;
import dalvik.system.BaseDexClassLoader;
import ihope_lib.MyReflect;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.mod.*;
import mindustry.mod.Mods.LoadedMod;
import modtools.graphics.MyShaders;
import modtools.ui.*;
import modtools.ui.tutorial.AllTutorial;
import modtools.utils.Tools;

import java.io.File;
import java.net.URLClassLoader;
import java.util.Arrays;

import static mindustry.Vars.ui;
import static modtools.utils.MySettings.SETTINGS;

public class ModTools extends Mod {
	/** 是否从游戏内导入进来的 */
	static boolean isImportFromGame = false;

	static {
		if (ui != null && ui.hudGroup != null) {
			isImportFromGame = true;
		}
	}

	@SuppressWarnings("Convert2MethodRef")
	public ModTools() {
		Log.info("Loaded ModTools constructor" + (isImportFromGame ? " [[[from game]]]" : "") + ".");

		if (isImportFromGame) try {
			resolveLibs();
			resolveUI();
		} catch (Throwable e) {
			Vars.ui.showException("Cannot load ModTools. (Don't worry)", e);
		}/* !isImportFromGame */
		else {
			Tools.forceRun(() -> {
				if (Vars.mods.getMod(ModTools.class) == null) return false;
				Log.info("ok");
				resolveLibs();
				return true;
			});
			Events.on(ClientLoadEvent.class, e -> resolveUI());
		}

	}
	public static Fi findRoot() {
		if (OS.isWindows) {
			return Fi.get(((URLClassLoader) ModTools.class.getClassLoader()).getURLs()[0].getFile());
		}
		return findRootAndroid();
	}
	public static Fi findRootAndroid() {
		Object pathList = Reflect.get(BaseDexClassLoader.class, ModTools.class.getClassLoader(),
		 "pathList");
		Object[] arr  = Reflect.get(pathList, "dexElements");
		File     file = Reflect.get(arr[0], "path");
		return new Fi(file);
	}
	private static void resolveLibs() {
		LoadedMod mod = Vars.mods.getMod(ModTools.class);
		root = mod != null ? mod.root : new ZipFi(findRoot());
		mainLoader = (ModClassLoader) Vars.mods.mainLoader();
		libs = root.child("libs");

		loadLib("reflect-core", "ihope_lib.MyReflect", true, () -> MyReflect.load());
		IntVars.hasDecompiler = loadLib("procyon-0.6", "com.strobel.decompiler.Decompiler", false);
		if (isImportFromGame) loadBundle();

		// MyReflect.classDefiner().defineClass();
		MyShaders.load();
	}
	private static void resolveUI() {
		if (throwable != null && ui != null) {
			ui.showException(throwable);
			return;
		}

		Time.runTask(6f, () -> {
			IntUI.load();
			AllTutorial.init();
		});
		if (SETTINGS.getBool("ShowMainMenuBackground")) {
			Tools.runIgnoredException(Background::main);
		}
	}
	/**
	 * @see Mods#buildFiles()
	 */
	public static void loadBundle() {
		ObjectMap<String, Seq<Fi>> bundles;
		//load up bundles.
		Fi folder = root.child("bundles");
		if (!folder.exists()) return;

		Fi[] files = Arrays.stream(folder.list()).filter(file ->
			file.name().startsWith("bundle") && file.extension().equals("properties"))
		 .toArray(Fi[]::new);
		bundles = new ObjectMap<>(files.length);
		for (Fi file : files) {
			String name = file.nameWithoutExtension();
			bundles.get(name, Seq::new).add(file);
		}

		I18NBundle bundle = Core.bundle;
		while (bundle != null) {
			String str    = bundle.getLocale().toString();
			String locale = "bundle" + (str.isEmpty() ? "" : "_" + str);
			if (bundles.containsKey(locale)) for (Fi file : bundles.get(locale)) {
				try {
					PropertiesUtils.load(bundle.getProperties(), file.reader());
				} catch (Throwable e) {
					Log.err("Error loading bundle: " + file + "/" + locale, e);
				}
			}
			bundle = bundle.getParent();
		}

	}

	public static ModClassLoader mainLoader;
	public static Fi             root;
	public static Throwable      throwable = null;
	public static Fi             libs;

	public static boolean loadLib(String fileName, String mainClassName, boolean showError) {
		return loadLib(fileName, mainClassName, showError, null);
	}

	public static boolean loadLib(String fileName, String mainClassName, boolean showError, Runnable callback) {
		try {
			// 没报错的话，证明已经加载
			mainLoader.loadClass(mainClassName);
			return true;
		} catch (Exception ignored) {}

		Log.info("Loading @.jar", fileName);
		// 加载反射
		try {
			Fi sourceFi = libs.child(fileName + ".jar");
			Log.info("Loading source fi: " + sourceFi);
			Fi toFi = Vars.dataDirectory.child("tmp/mod-tools-" + fileName + ".jar");
			delete(toFi);
			sourceFi.copyTo(toFi);
			ClassLoader loader = Vars.platform.loadJar(toFi, mainLoader);
			mainLoader.addChild(loader);
			Class.forName(mainClassName, true, loader);
			if (callback != null) callback.run();
			toFi.delete();
			return true;
		} catch (Throwable e) {
			if (showError) {
				throwable = e;
				Log.err(e);
			}
			return false;
		}
	}
	/** @return {@code true} if the file be deleted successfully */
	private static boolean delete(Fi fi) {
		return fi.exists() && (fi.isDirectory() ? fi.deleteDirectory() : fi.delete());
	}
	/*public static void test() {
		new Thread(() -> Core.app.exit());
		unsafe.park(false, (long) 1E9);
		try {
			Method m = Application.class.getDeclaredMethod("getListeners");
			m.setAccessible(true);
			Seq<ApplicationListener> listeners = (Seq<ApplicationListener>) m.invoke(Core.app);
			listeners.remove((ApplicationListener) Vars.platform);
			Field field = SdlApplication.class.getDeclaredField("running");
			field.setAccessible(true);
			m = SdlApplication.class.getDeclaredMethod("loop");
			m.setAccessible(true);
			while (field.getBoolean(Core.app)) {
				m.invoke(Core.app);
			}
			Log.info("finish");
		} catch (Throwable e) {
			Log.err(e);
		}
	}*/
}