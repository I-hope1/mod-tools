package modtools;

import arc.Events;
import arc.files.Fi;
import arc.util.*;
import ihope_lib.MyReflect;
import mindustry.Vars;
import mindustry.game.EventType.*;
import mindustry.mod.*;
import modtools.graphics.MyShaders;
import modtools.ui.*;
import modtools.utils.*;

import static mindustry.Vars.*;
import static modtools.utils.MySettings.SETTINGS;

public class ModTools extends Mod {
	public ModTools() {
		Log.info("Loaded ModTools constructor.");


		Tools.forceRun(() -> {
			if (Vars.mods.getMod(ModTools.class) == null) return false;
			root = Vars.mods.getMod(ModTools.class).root;
			mainLoader = (ModClassLoader) Vars.mods.mainLoader();
			libs = root.child("libs");
			if (loadLib("reflect-core", "ihope_lib.MyReflect", true)) MyReflect.load();
			IntVars.hasDecomplier = loadLib("procyon-0.6", "com.strobel.decompiler.Decompiler", false);

			MyShaders.load();
			return true;
		});
		Events.on(ClientLoadEvent.class, e -> {
			if (throwable != null) {
				ui.showException(throwable);
				return;
			}
			/* JSFunc.testElement(new DesignTable<>(new Table()) {{
				template.image().size(42);
			}}); */
			// Time.runTask(100f, () -> JSFunc.testElement(new MyLabel("开始", IntStyles.myLabel)));

			// texture.getTextureData();
			// 加载字体
			// Core.app.post(MyFonts::load);

			// Unit135G.main();
			Time.runTask(6f, () -> {
				/*BaseDialog dialog = new BaseDialog("frog");
				dialog.addCloseListener();

				Table cont = dialog.cont;
				cont.image(Core.atlas.find(modName + "-frog")).pad(20f).row();
				cont.add("behold").row();
				Objects.requireNonNull(dialog);
				cont.button("I see", dialog::hide).size(100f, 50f);
				dialog.show();*/
				//noinspection Convert2MethodRef
				IntUI.load();
			});
			if (SETTINGS.getBool("ShowMainMenuBackground")) {
				try {
					Background.main();
				} catch (Throwable ignored) {}
			}
			// JSFunc.testElement(new Image(Fonts.def.getRegion()));
		});
	}

	public static ModClassLoader mainLoader;
	public static Fi             root;
	public static Throwable      throwable = null;
	public static Fi             libs;

	public static boolean loadLib(String fileName, String mainClassName, boolean showError) {
		try {
			// 没错误 证明已经加载
			Class.forName(mainClassName, false, mainLoader);
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
			toFi.delete();
			return true;
		} catch (Throwable e) {
			if (showError) throwable = e;
			Log.err(e);
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