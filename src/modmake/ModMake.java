
package modmake;

import arc.Core;
import arc.Events;
import arc.scene.ui.TextButton;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType.*;
import mindustry.mod.*;
import mindustry.type.Item;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.gen.Icon;

public class ModMake extends Mod {
	public ModMake() {
		Log.info("Loaded ExampleJavaMod constructor.");
		// listen for game load event
		Events.on(ClientLoadEvent.class, e -> {
			// show dialog upon startup
			Time.runTask(10f, () -> {
				BaseDialog dialog = new BaseDialog("frog");
				dialog.cont.add("behold").row();
				/*
				 * mod sprites are prefixed with the mod name (this mod is called
				 * 'example-java-mod' in its config)
				 */
				dialog.cont.image(Core.atlas.find("mod.make--java-mod-frog")).pad(20f).row();
				/*
				 * TextButton b = new TextButton("test");
				 * dialog.cont.add(b).row();
				 * b.clicked(() -> {
				 * Item[] item = { null };
				 * UI.showSelectImageTable(b, Vars.content.items(), () -> item[0],
				 * str -> Vars.ui.showInfo((item[0] = str).localizedName),
				 * 40, 32, 6, false);
				 * });
				 */
				IntVars.load();
				dialog.cont.button("I see", dialog::hide).size(100f, 50f);
				dialog.show();
			});
		});
	}

	public void loadContent() {
	}
}
