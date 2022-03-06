
package modmake;

import arc.Core;
import arc.Events;
import arc.graphics.Texture;
import arc.scene.Group;
import arc.scene.ui.Image;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.mod.Mod;
import mindustry.mod.Mods.LoadedMod;
import mindustry.ui.dialogs.BaseDialog;

public class ModMake extends Mod {

	public ModMake() {
		Log.info("Loaded ModMake constructor.");
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
				dialog.cont.image(Core.atlas.find(IntVars.modName + "-frog")).pad(20f).row();
				IntVars.load();
				dialog.addCloseListener();
				dialog.cont.button("I see", dialog::hide).size(100f, 50f);
				dialog.show();
			});

			Group group = (Group) Vars.ui.menuGroup.getChildren().get(0);
			var children = group.getChildren();
			children.get(0).remove();
			LoadedMod mod = Vars.mods.getMod(IntVars.modName);
			Image img = new Image(new Texture(mod.root.child("test.png")));
			group.addChildAt(0, img);
		});
	}

}
