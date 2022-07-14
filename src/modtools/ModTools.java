package modtools;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.mod.Mod;
import mindustry.mod.ModClassLoader;
import mindustry.ui.dialogs.BaseDialog;
import modtools.ui.Background;
import modtools_lib.MyReflect;

import java.util.Objects;

import static modtools.IntVars.modName;

public class ModTools extends Mod {
	public static ModClassLoader mainLoader;

	public ModTools() {
		Log.info("Loaded ModTools constructor.");
		Time.runTask(1, ModTools::loadReflect);

		Events.on(EventType.ClientLoadEvent.class, e -> {
			try {
				Class.forName("modtools_lib.MyReflect", true, mainLoader);
			} catch (Exception ex) {
				return;
			}
			/*try {
				MyPlacement.load();
			} catch (Throwable ex) {
				Log.err(ex);
			}*/
			Time.runTask(10f, () -> {
				BaseDialog dialog = new BaseDialog("frog");
				dialog.addCloseListener();

				Table cont = dialog.cont;
				cont.image(Core.atlas.find(modName + "-frog")).pad(20f).row();
				cont.add("behold,ok").row();
				Objects.requireNonNull(dialog);
				cont.button("I see", dialog::hide).size(100f, 50f);
				dialog.show();
			});
			IntVars.load();

			if (Core.settings.getBool(modName + "-ShowMainMenuBackground")) Background.main();
		});
	}

	public static boolean init = false;
	public static void loadReflect() {
		if (init) return;
		init = true;
		// 加载前置
		try {
			mainLoader = (ModClassLoader) Vars.mods.mainLoader();
			Fi sourceFi = Vars.mods.locateMod("mod-tools").root
					.child("libs").child("mod-tools-" + (Vars.mobile ? "Android" : "Desktop") + "-lib.jar");
			Fi toFi = Vars.dataDirectory.child("tmp/mod-tools-lib.jar");
			if (toFi.isDirectory()) toFi.deleteDirectory();
			if (!toFi.exists()) toFi.writeString("");
			sourceFi.copyTo(toFi);
			ClassLoader loader = Vars.platform.loadJar(toFi, mainLoader);
			mainLoader.addChild(loader);
			Class.forName("modtools_lib.MyReflect", true, loader);
			toFi.delete();
		} catch (Exception e) {
			Vars.ui.showException(e);
		}
		MyReflect.load();
	}
}
