
package modtools.ui;

import arc.Core;
import arc.graphics.Texture;
import arc.graphics.g2d.TextureRegion;
import arc.scene.*;
import arc.scene.ui.Image;
import arc.util.*;
import mindustry.Vars;
import modtools.IntVars;
import modtools.struct.LazyValue;

import static modtools.IntVars.root;

public class Background {
	public static void load() {
		Element tmp = Vars.ui.menuGroup.getChildren().first();
		if (!(tmp instanceof Group group)) return;
		Element render = group.getChildren().first();
		if (!(render.getClass().isAnonymousClass()
		      && render.getClass().getEnclosingClass() == Group.class
		      && render.getClass().getSuperclass() == Element.class)) return;
		render.clear();
		// render.remove();

		// Draw.rect(Draw.wrap(Core.graphics.isPortrait() ? portrait : landscape), 0, 0);
		Image img = new Image(new TextureRegion());
		img.setFillParent(true);
		IntVars.addResizeListener(() -> setRegion(img));
		Time.runTask(4f, () -> {
			group.addChildAt(0, img);
			setRegion(img);
		});
	}
	private static void setRegion(Image img) {
		img.getRegion().set(getTexture());
	}

	/* 懒加载 */
	static LazyValue<Texture> landscape = LazyValue.of(() -> new Texture(root.child("横屏.png")));
	static LazyValue<Texture> portrait  = LazyValue.of(() -> new Texture(root.child("竖屏.png")));
	private static Texture getTexture() {
		boolean isPortrait = Core.graphics.isPortrait();
		return (isPortrait ? portrait : landscape).get();
	}
}
