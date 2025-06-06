
package modtools.ui;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Texture;
import arc.graphics.g2d.TextureRegion;
import arc.scene.*;
import arc.scene.ui.Image;
import arc.util.Time;
import mindustry.Vars;
import modtools.IntVars;
import modtools.struct.LazyValue;

import static modtools.IntVars.*;

public class Background {
	static Group group;
	public static void load() {
		Element tmp = Vars.ui.menuGroup.getChildren().first();
		if (!(tmp instanceof Group g)) return;
		group = g;
		Element render = group.getChildren().first();
		if (!(render.getClass().isAnonymousClass()
		      && render.getClass().getEnclosingClass() == Group.class
		      && render.getClass().getSuperclass() == Element.class)) return;
		render.visible = false;
		// render.remove();

		// Draw.rect(Draw.wrap(Core.graphics.isPortrait() ? portrait : landscape), 0, 0);
		Image img = new Image(new TextureRegion()) {};
		img.setFillParent(true);
		IntVars.addResizeListener(() -> setRegion(img));
		Time.runTask(3f * Time.delta, () -> {
			group.addChildAt(0, img);
			setRegion(img);
		});
	}
	private static void setRegion(Image img) {
		img.getRegion().set(getTexture());
	}

	/* 懒加载 */
	static final Fi backgroundDir = dataDirectory.child("backgrounds");
	static final Fi landscapeDir  = backgroundDir.child("landscape");
	static final Fi portraitDir   = backgroundDir.child("portrait");

	static {
		checkFile(landscapeDir);
		checkFile(portraitDir);
	}

	private static void checkFile(Fi dir) {
		if (!dir.exists()) {
			dir.mkdirs();
			root.child(dir.name() + ".png").copyTo(dir);
		} else {
			dir.mkdirs();
		}
	}

	static final LazyValue<Texture> landscape = LazyValue.of(() -> new Texture(landscapeDir.findAll().random()));
	static final LazyValue<Texture> portrait  = LazyValue.of(() -> new Texture(portraitDir.findAll().random()));
	private static Texture getTexture() {
		boolean isPortrait = Core.graphics.isPortrait();
		return (isPortrait ? portrait : landscape).get();
	}
	public static void dispose() {
		landscape.get().dispose();
		portrait.get().dispose();
		Element first = group.getChildren().first();
		if (first != null && first.getClass().getClassLoader() == Background.class.getClassLoader()) {
			first.remove();
		}
		group.getChildren().first().visible = true;
	}
}
