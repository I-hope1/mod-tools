package modtools.ui;

import arc.graphics.Texture;
import arc.scene.Group;
import arc.scene.ui.Image;
import mindustry.Vars;
import mindustry.mod.Mods;
import modtools.IntVars;

public class Background {
	public static void main() {
		Group group = (Group) Vars.ui.menuGroup.getChildren().get(0);
		var children = group.getChildren();
		children.get(0).remove();
		Mods.LoadedMod mod = Vars.mods.getMod(IntVars.modName);
		Image img = new Image(new Texture(mod.root.child("test.png")));
		img.setFillParent(true);
		group.addChildAt(0, img);
	}
}
