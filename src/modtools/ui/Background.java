
package modtools.ui;

import arc.ApplicationListener;
import arc.Core;
import arc.graphics.Texture;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.ui.Image;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.mod.Mods.LoadedMod;
import modtools.ModTools;

public class Background {

	public static void main() {
		Group group = (Group) Vars.ui.menuGroup.getChildren().get(0);
		Seq<Element> children = group.getChildren();
		children.get(0).clear();
		children.get(0).remove();
		LoadedMod mod = Vars.mods.getMod(ModTools.class);

		Texture landscape = new Texture(mod.root.child("横屏.png")), portrait = new Texture(mod.root.child("竖屏.png"));
		Image img = new Image(Core.graphics.isPortrait() ? portrait : landscape);
//		img.rotation = Core.graphics.isPortrait() ? 90 : 0;
		img.setFillParent(true);
		Core.app.addListener(new ApplicationListener() {
			@Override
			public void resize(int width, int height) {
				img.getRegion().set(Core.graphics.isPortrait() ? portrait : landscape);
			}
		});
		group.addChildAt(0, img);
	}
}
