
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

public class Background {
	static Texture landscape, portrait;
	static Texture landscape() {
		if (landscape == null) landscape = new Texture(root.child("横屏.png"));
		return landscape;
	}
	static Texture portrait() {
		if (portrait == null) portrait = new Texture(root.child("竖屏.png"));
		return portrait;
	}
	public static void load() {
		// EntityShow.main();
		Element tmp = Vars.ui.menuGroup.getChildren().get(0);
		if (!(tmp instanceof Group group)) return;
		Element childrenFirst = group.getChildren().first();
		childrenFirst.clear();
		childrenFirst.remove();

		// Draw.rect(Draw.wrap(Core.graphics.isPortrait() ? portrait : landscape), 0, 0);
		Image img = new Image(new TextureRegion());
		//		img.rotation = Core.graphics.isPortrait() ? 90 : 0;
		img.setFillParent(true);
		IntVars.addResizeListener(() -> {
			img.getRegion().set(Core.graphics.isPortrait() ? portrait() : landscape());
		});
		Time.runTask(4f, () -> {
			group.addChildAt(0, img);
			img.getRegion().set(Core.graphics.isPortrait() ? portrait() : landscape());
		});
	}
}
