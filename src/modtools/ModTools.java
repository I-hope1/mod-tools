package modtools;

import arc.Events;
import arc.files.Fi;
import arc.util.*;
import ihope_lib.MyReflect;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.mod.*;
import modtools.graphics.MyShaders;
import modtools.ui.*;
import modtools.ui.tutorial.AllTutorial;
import modtools.utils.Tools;

import static mindustry.Vars.ui;
import static modtools.utils.MySettings.SETTINGS;

public class ModTools extends Mod {
	public ModTools() {
		Log.info("Loaded ModTools constructor.");

		Tools.forceRun(() -> {
			if (Vars.mods.getMod(ModTools.class) == null) return false;
			root = Vars.mods.getMod(ModTools.class).root;
			mainLoader = (ModClassLoader) Vars.mods.mainLoader();
			libs = root.child("libs");
			loadLib("reflect-core", "ihope_lib.MyReflect", true, () -> MyReflect.load());
			IntVars.hasDecomplier = loadLib("procyon-0.6", "com.strobel.decompiler.Decompiler", false);

			// MyReflect.classDefiner().defineClass();
			MyShaders.load();
			return true;
		});
		/* Tools.forceRun(() -> {
			Fonts.outline = Fonts.icon = Fonts.def = MSYHMONO;
			return false;
		}); */

		Events.on(ClientLoadEvent.class, e -> {
			if (throwable != null) {
				ui.showException(throwable);
				return;
			}

			Time.runTask(6f, () -> {
				//noinspection Convert2MethodRef
				IntUI.load();
				AllTutorial.init();
			});
			if (SETTINGS.getBool("ShowMainMenuBackground")) {
				try {
					Background.main();
				} catch (Throwable ignored) {}
			}
		});
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
			// 没错误 证明已经加载
			mainLoader.loadClass(mainClassName);
			return true;
		} catch (Exception ignored) {}
		Log.info("Loading @.jar", fileName);
		// 加载反射
		try {
			Fi sourceFi = libs.child(fileName + ".jar");
			Log.info("Load source fi: " + sourceFi);
			Fi toFi = Vars.dataDirectory.child("tmp/mod-tools-" + fileName + ".jar");
			if (toFi.exists()) {
				if (toFi.isDirectory()) {
					toFi.deleteDirectory();
				} else {
					toFi.delete();
				}
			}
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