
package modtools.ui;

import arc.*;
import arc.graphics.*;
import arc.scene.*;
import arc.scene.ui.Image;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.mod.Mods.LoadedMod;
import modtools.*;

public class Background {

	public static void main() {
		// EntityShow.main();
		Element tmp = Vars.ui.menuGroup.getChildren().get(0);
		if (!(tmp instanceof Group)) return;
		Group        group    = (Group) tmp;
		Seq<Element> children = group.getChildren();
		children.get(0).clear();
		children.get(0).remove();
		LoadedMod mod = Vars.mods.getMod(ModTools.class);

		Texture landscape = new Texture(mod.root.child("横屏.png")), portrait = new Texture(mod.root.child("竖屏.png"));
		Image   img       = new Image(Pixmaps.blankTexture());
		//		img.rotation = Core.graphics.isPortrait() ? 90 : 0;
		img.setFillParent(true);
		IntVars.addResizeListener(() -> {
			img.getRegion().set(Core.graphics.isPortrait() ? portrait : landscape);
		});
		group.addChildAt(0, img);
		Time.runTask(6f, () -> {
			img.getRegion().set(Core.graphics.isPortrait() ? portrait : landscape);
		});
	}
}
