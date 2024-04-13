
package modtools.ui;

import arc.Core;
import arc.graphics.Texture;
import arc.graphics.g2d.TextureRegion;
import arc.scene.*;
import arc.scene.ui.Image;
import arc.util.Time;
import mindustry.Vars;
import modtools.IntVars;
import modtools.struct.LazyValue;

import static modtools.IntVars.root;

public class Background {
	public static void load() {
		Element tmp = Vars.ui.menuGroup.getChildren().get(0);
		if (!(tmp instanceof Group group && tmp.getClass().isAnonymousClass()
		      && tmp.getClass().getEnclosingClass() == Group.class
		      && tmp.getClass().getSuperclass() == Element.class)) return;
		Element childrenFirst = group.getChildren().first();
		childrenFirst.clear();
		childrenFirst.remove();

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
