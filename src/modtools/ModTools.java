package modtools;

import arc.*;
import arc.files.Fi;
import arc.func.Boolf2;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.util.*;
import mindustry.Vars;
import mindustry.android.AndroidRhinoContext.AndroidContextFactory;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.mod.*;
import modtools.ui.*;
import modtools.ui.components.FrameLabel;
import modtools.utils.Tools;
import modtools_lib.MyReflect;
import rhino.*;

import java.lang.reflect.Field;

import static mindustry.Vars.ui;
import static modtools.utils.MySettings.settings;

public class ModTools extends Mod {
	public static ModClassLoader mainLoader;

	public ModTools() {
		Log.info("Loaded ModTools constructor.");
		// Core.graphics.clear(Color.clear);
		// ApplicationCore
		Tools.forceRun(() -> {
			if (Vars.mods.getMod(ModTools.class) == null) throw new RuntimeException();
			Log.info("Loaded Reflect.");
			loadReflect();
		});

		// Log.debug(MethodHandles.Im);
		Events.on(ClientLoadEvent.class, e -> {
			if (throwable != null) {
				ui.showException(throwable);
				return;
			}

			// texture.getTextureData();
			MyFonts.load();

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
				IntVars.load();
				if (settings.getBool("ShowMainMenuBackground")) {
					try {
						Background.main();
					} catch (Throwable ignored) {}
				}
			});

		});
	}

	public static Throwable throwable = null;

	public static void loadReflect() {
		mainLoader = (ModClassLoader) Vars.mods.mainLoader();
		try {
			// 没错误 证明已经加载
			Class.forName("modtools_lib.MyReflect", false, mainLoader);
			return;
		} catch (Exception ignored) {}
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
			Class.forName("modtools_lib.MyReflect", true, loader);
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
