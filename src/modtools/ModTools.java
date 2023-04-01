package modtools;

import arc.*;
import arc.files.Fi;
import arc.util.*;
import ihope_lib.MyReflect;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.mod.*;
import mindustry.type.*;
import mindustry.world.Block;
import mindustry.world.blocks.production.GenericCrafter;
import modtools.ui.*;
import modtools.utils.Tools;

import static mindustry.Vars.ui;
import static modtools.utils.MySettings.settings;

public class ModTools extends Mod {
	public ModTools() {
		Log.info("Loaded ModTools constructor.");


		Tools.forceRun(() -> {
			if (Vars.mods.getMod(ModTools.class) == null) return false;
			root = Vars.mods.getMod(ModTools.class).root;
			loadReflect();

			return true;
		});
		Events.on(ClientLoadEvent.class, e -> {
			if (throwable != null) {
				ui.showException(throwable);
				return;
			}

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
				IntVars.load();
			});
			if (settings.getBool("ShowMainMenuBackground")) {
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

	public static void loadReflect() {
		mainLoader = (ModClassLoader) Vars.mods.mainLoader();

		try {
			// 没错误 证明已经加载
			Class.forName("ihope_lib.MyReflect", false, mainLoader);
			return;
		} catch (Exception ignored) {}
		Log.info("Loaded Reflect.");
		// 加载反射
		try {
			Fi sourceFi = Vars.mods.getMod(ModTools.class).root
					.child("libs").child("lib.jar");
			Log.info("load source fi: " + sourceFi);
			Fi toFi = Vars.dataDirectory.child("tmp/mod-tools-lib.jar");
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
			Class.forName("ihope_lib.MyReflect", true, loader);
			toFi.delete();
			MyReflect.load();
		} catch (Throwable e) {
			throwable = e;
			Log.err(e);
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