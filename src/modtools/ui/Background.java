
package modtools.ui;

import arc.graphics.Texture;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.ui.Image;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.mod.Mods.LoadedMod;

import static modtools.IntVars.modName;

public class Background {

	public static void main() {
		Group group = (Group) Vars.ui.menuGroup.getChildren().get(0);
		Seq<Element> children = group.getChildren();
		children.get(0).clear();
		children.get(0).remove();
		LoadedMod mod = Vars.mods.getMod(modName);
		Image img = new Image(new Texture(mod.root.child("test.png")));
//		img.rotation = Core.graphics.isPortrait() ? 90 : 0;
		img.setFillParent(true);
		group.addChildAt(0, img);
	}
}
