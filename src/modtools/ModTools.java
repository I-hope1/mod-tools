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

import java.util.Objects;

import static modtools.IntVars.modName;

public class ModTools extends Mod {
	public ModTools() {
		Log.info("Loaded ModMake constructor.");
		Events.on(EventType.ClientLoadEvent.class, e -> {
			Time.runTask(10.0f, () -> {
				BaseDialog dialog = new BaseDialog("frog");
				dialog.addCloseListener();

				Table cont = dialog.cont;
				cont.image(Core.atlas.find(modName + "-frog")).pad(20.0f).row();
				cont.add("behold").row();
				Objects.requireNonNull(dialog);
				cont.button("I see", dialog::hide).size(100.0f, 50.0f);
				dialog.show();
			});
			IntVars.load();
			Background.main();
		});
	}

}
