
package modtools.ui;

import arc.Core;
import arc.graphics.Texture;
import arc.graphics.g2d.TextureRegion;
import arc.scene.*;
import arc.scene.ui.Image;
import arc.util.Time;
import mindustry.Vars;
import modtools.IntVars;

import static modtools.IntVars.root;
import static modtools.ui.Background.L0.*;
import static modtools.ui.Background.L1.*;

public class Background {
	/* 懒加载 */
	interface L0 {
		Texture landscape = new Texture(root.child("横屏.png"));
	}
	interface L1 {
		Texture portrait = new Texture(root.child("竖屏.png"));
	}
	public static void load() {
		Element tmp = Vars.ui.menuGroup.getChildren().get(0);
		if (!(tmp instanceof Group group)) return;
		Element childrenFirst = group.getChildren().first();
		childrenFirst.clear();
		childrenFirst.remove();

		// Draw.rect(Draw.wrap(Core.graphics.isPortrait() ? portrait : landscape), 0, 0);
		Image img = new Image(new TextureRegion());
		//		img.rotation = Core.graphics.isPortrait() ? 90 : 0;
		img.setFillParent(true);
		IntVars.addResizeListener(() -> setRegion(img));
		Time.runTask(4f, () -> {
			group.addChildAt(0, img);
			setRegion(img);
		});
	}
	private static void setRegion(Image img) {
		img.getRegion().set(Core.graphics.isPortrait() ? portrait : landscape);
	}
}
