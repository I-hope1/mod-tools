package modtools;

import arc.Core;
import arc.Events;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import arc.util.Time;
import mindustry.game.EventType;
import mindustry.mod.Mod;
import mindustry.ui.dialogs.BaseDialog;
import modtools.ui.Background;
import modtools.ui.MyReflect;

import java.util.Objects;

import static modtools.IntVars.modName;

public class ModTools extends Mod {
	public ModTools() {
		Log.info("Loaded ModMake constructor.");
		MyReflect.load();

		Events.on(EventType.ClientLoadEvent.class, e -> {
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
				cont.add("behold").row();
				Objects.requireNonNull(dialog);
				cont.button("I see", dialog::hide).size(100f, 50f);
				dialog.show();
			});
			IntVars.load();
			if (Core.settings.getBool(modName + "-ShowMainMenuBackground")) Background.main();
		});

	}

}
