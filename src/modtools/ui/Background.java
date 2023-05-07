
package modtools.ui;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.Draw;
import arc.scene.*;
import arc.scene.ui.Image;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.mod.Mods.LoadedMod;
import modtools.*;
import modtools.graphics.MyShaders;

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
		// Draw.rect(Draw.wrap(Core.graphics.isPortrait() ? portrait : landscape), 0, 0);
		Image img = new Image(Pixmaps.blankTexture()) {
			public void draw() {
				// Shader last = Draw.getShader();
				// MyShaders.Specl.setUniformf("u_mix_color", 1, 0, 1);
				// Draw.blit(getRegion().texture, MyShaders.Specl);
				super.draw();
			}
		};
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
