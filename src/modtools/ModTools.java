package modtools;

import arc.*;
import arc.files.*;
import arc.scene.event.VisibilityListener;
import arc.struct.*;
import arc.util.*;
import arc.util.io.PropertiesUtils;
import dalvik.system.BaseDexClassLoader;
import ihope_lib.MyReflect;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.mod.*;
import mindustry.mod.Mods.*;
import modtools.graphics.MyShaders;
import modtools.ui.*;
import modtools.ui.content.debug.Tester;
import modtools.ui.tutorial.AllTutorial;
import modtools.utils.Tools;
import modtools.utils.ui.FileUtils;

import java.io.File;
import java.net.URLClassLoader;
import java.util.Arrays;

import static mindustry.Vars.*;
import static modtools.IntVars.root;
import static modtools.utils.MySettings.SETTINGS;

public class ModTools extends Mod {
	/** 是否从游戏内导入进来的 */
	static boolean isImportFromGame = false;

	static {
		if (ui != null && ui.hudGroup != null) {
			isImportFromGame = true;
		}
	}

	public ModTools() {
		Log.info("Loaded ModTools constructor" + (isImportFromGame ? " [[[from game]]]" : "") + ".");
		if (headless) Log.info("Running in headless environment.");

		ObjectMap<Class<?>, ModMeta> metas = Reflect.get(Mods.class, Vars.mods, "metas");
		IntVars.meta = metas.get(ModTools.class);

		try {
			if (!isImportFromGame) IntVars.meta.hidden = false;
			resolveLibsCatch();

			// HopeCall.init();
			if (isImportFromGame) resolveInputAndUI();
			else Events.on(ClientLoadEvent.class, e -> resolveInputAndUI());
		} catch (Throwable e) {
			if (isImportFromGame) Vars.ui.showException("Cannot load ModTools. (Don't worry.)", e);
			Log.err("Failed to load ModTools.", e);
		}
	}


	public void loadContent() {
		IntVars.meta.hidden = true;
		// Time.mark();
		Tester.initExecution();
		// Log.info("Initialized Execution in @ms", Time.elapsed());
	}
	public static Fi findRoot() {
		if (OS.isWindows || OS.isMac) {
			return Fi.get(((URLClassLoader) ModTools.class.getClassLoader()).getURLs()[0].getFile());
		}
		if (OS.isAndroid) return findRootAndroid();
		throw new UnsupportedOperationException();
	}
	private static String getFileName(String file) {
		int index = file.lastIndexOf('/');
		return file.substring(index).replace("/", "");
	}
	public static Fi findRootAndroid() {
		Object pathList = Reflect.get(BaseDexClassLoader.class, ModTools.class.getClassLoader(),
		 "pathList");
		Object[] arr  = Reflect.get(pathList, "dexElements");
		File     file = Reflect.get(arr[0], "path");
		return new Fi(file);
	}

	private static void resolveLibsCatch() {
		try {
			resolveLibs();
		} catch (UnexpectedPlatform e) {
			Log.err("It seems you platform is special. (But don't worry.)");
			Tools.forceRun(() -> {
				if (Vars.mods.getMod(ModTools.class) == null) return false;
				resolveLibs();
				return true;
			});
		}
	}
	private static void resolveLibs() {
		LoadedMod mod = Vars.mods.getMod(ModTools.class);
		root = mod != null ? mod.root : new ZipFi(findRoot());
		libs = root.child("libs");

		loadLib("reflect-core", "ihope_lib.MyReflect", true, () -> MyReflect.load());
		// setLoadRenderer();
		IntVars.hasDecompiler = loadLib("procyon-0.6", "com.strobel.decompiler.Decompiler", false);
		if (isImportFromGame) loadBundle();

		// MyReflect.classDefiner().defineClass();
	}

	private static void resolveInputAndUI() {
		if (ui == null) return;
		Time.mark();
		if (error != null) {
			ui.showException(error);
			return;
		}
		HopeInput.load();
		if (OS.isWindows || OS.isMac) {
			ui.mods.addListener(new VisibilityListener() {
				public boolean shown() {
					ui.mods.removeListener(this);
					ui.mods.buttons.row().button("importFromSelector", () -> {
						FileUtils.openFiSelector(list -> {
							try {
								for (Fi fi : list) {
									if (!fi.extEquals("zip") && !fi.extEquals("jar"))
										throw new IllegalArgumentException("Unexpected file type: " + fi.extension());
									Vars.mods.importMod(fi);
								}
							} catch (Throwable e) {
								IntUI.showException("Failed to import mod from selector", e);
							}
						});
					});
					return false;
				}
			});
		}
		MyShaders.load();
		Time.runTask(6f, () -> {
			IntUI.load();
			AllTutorial.init();
			// Circle.draw();
		});
		if (SETTINGS.getBool("ShowMainMenuBackground")) {
			Tools.runIgnoredException(Background::main);
		}
		Log.info("Loaded ModTools input and ui in @ms", Time.elapsed());
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

	public static Throwable error = null;
	public static Fi        libs;

	public static boolean loadLib(String fileName, String mainClassName, boolean showError) {
		return loadLib(fileName, mainClassName, showError, null);
	}
	public static boolean loadLib(String fileName, String mainClassName, boolean showError, Runnable callback) {
		try {
			// 没报错的话，证明已经加载
			IntVars.mainLoader.loadClass(mainClassName);
			return true;
		} catch (Exception ignored) {}

		Fi sourceFi = libs.child(fileName + ".jar");
		if (!sourceFi.exists()) return false;

		Log.info("Loading @.jar", fileName);
		Time.mark();
		// 加载前置
		try {
			Fi toFi = Vars.dataDirectory.child("tmp/mod-tools-" + fileName + ".jar");
			IntVars.delete(toFi);
			sourceFi.copyTo(toFi);
			ClassLoader loader = Vars.platform.loadJar(toFi, IntVars.mainLoader);
			IntVars.mainLoader.addChild(loader);
			Class.forName(mainClassName, true, loader);
			if (callback != null) callback.run();
			toFi.delete();
			return true;
		} catch (Throwable e) {
			if (showError) {
				error = e;
				Log.err("Unexpected exception when loading '" + sourceFi + "'", e);
			}
			return false;
		} finally {
			Log.info("Loaded '@' in @ms", sourceFi, Time.elapsed());
		}
	}

	static class UnexpectedPlatform extends RuntimeException {}

}