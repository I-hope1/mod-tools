package modtools;

import arc.*;
import arc.files.Fi;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.Texture.TextureFilter;
import arc.graphics.g2d.*;
import arc.math.Mathf;
import arc.struct.*;
import arc.util.*;
import arc.util.Http.HttpStatusException;
import mindustry.Vars;
import mindustry.android.AndroidRhinoContext.AndroidContextFactory;
import mindustry.content.*;
import mindustry.ctype.*;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.graphics.g3d.PlanetGrid.Ptile;
import mindustry.io.JsonIO;
import mindustry.mod.*;
import mindustry.mod.Mods.ModMeta;
import mindustry.type.*;
import mindustry.ui.dialogs.ModsDialog;
import mindustry.world.Block;
import modtools.ui.*;
import modtools.ui.components.FrameLabel;
import modtools.utils.Tools;
import modtools_lib.MyReflect;
import rhino.*;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Date;

import static mindustry.Vars.ui;
import static modtools.utils.MySettings.settings;
import static modtools_lib.MyReflect.unsafe;

public class ModTools extends Mod {
	public static ModClassLoader mainLoader;

	public ModTools() {
		Log.info("Loaded ModTools constructor.");
		// Log.info("A2");
		// ApplicationCore

		Tools.forceRun(() -> {
			if (Vars.mods.getMod(ModTools.class) == null) throw new RuntimeException();
			Log.info("Loaded Reflect.");
			loadReflect();
			/*Field field;
			try {
				field = Vars.class.getDeclaredField("defaultContentIcons");
				unsafe.putObject(Vars.class,
						unsafe.staticFieldOffset(field),
						Seq.with(ContentType.all)
								.filter(t -> t.contentClass != null && UnlockableContent.class.isAssignableFrom(t.contentClass))
								.toArray(ContentType.class));
			} catch (Exception e) {
				Log.err(e);
			}*/
		});
		/*Tools.forceRun(() -> {
			var sectors = Planets.serpulo.sectors;
			Log.info("loaded replace");
			sectors.replace(MySactor::new);
		});*/

		// Log.debug(MethodHandles.Im);

		/*Block blockAAA = new Block("blockAAA");
		Block blockBBB = new Block("blockBBB");*/
		Events.on(ClientLoadEvent.class, e -> {
			if (throwable != null) {
				ui.showException(throwable);
				return;
			}
			Planets.serpulo.sectors.set(171, new MySactor(Planets.serpulo.sectors.get(171)) {{
				threat = 10;
			}});

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

			/*TechTree.nodeRoot("AAA", blockAAA, () -> {
				TechTree.node(blockBBB
						, ItemStack.with(Items.copper, 100), () -> {});
			});*/
		});
	}

	public static Throwable throwable = null;

	public static class MySactor extends Sector {
		public MySactor(Sector sector) {
			super(sector.planet, sector.tile);
			threat = sector.threat;
			generateEnemyBase = sector.generateEnemyBase;
			save = sector.save;
			preset = sector.preset;
			info = sector.info;
		}

		public String displayThreat() {
			float step = 0.2f;
			String color = Tmp.c1.set(Color.white).lerp(Color.scarlet, Mathf.round(threat, step)).toString();
			String[] threats = {"low", "medium", "high", "extreme", "eradication", "hell"};
			int index = Math.min((int) (threat / step), threats.length - 1);
			return "[#" + color + "]" + Core.bundle.get("threat." + threats[index]);
		}

	}

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
