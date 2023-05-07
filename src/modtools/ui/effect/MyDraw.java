package modtools.ui.effect;

import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.struct.*;
import mindustry.graphics.Pal;

import static arc.Core.graphics;
import static modtools.ui.Contents.settingsUI;
import static modtools.utils.MySettings.D_BLUR;

public class MyDraw {
	public static void dashLine(float thick, Color color, float x, float y, float x2, float y2, int segments) {
		Lines.stroke(thick);
		Draw.color(Pal.gray, color.a);
		Lines.dashLine(x, y, x2, y2, segments);
		Lines.stroke(thick / 3f, color);
		Lines.dashLine(x, y, x2, y2, segments);
		Draw.reset();
		//			Log.info(segments);
	}

	public static void dashLine(float thick, Color color, float x, float y, float x2, float y2) {
		dashLine(thick, color, x, y, x2, y2, (int) (Math.max(Math.abs(x - x2), Math.abs(y - y2)) / 5f));
	}

	public static void dashRect(float thick, Color color, float x, float y, float width, float height) {
		dashLine(thick, color, x, y, x + width, y);
		dashLine(thick, color, x + width, y, x + width, y + height);
		dashLine(thick, color, x + width, y + height, x, y + height);
		dashLine(thick, color, x, y + height, x, y);
	}

	public static void dashSquare(float thick, Color color, float x, float y, float size) {
		dashRect(thick, color, x - size / 2f, y - size / 2f, size, size);
	}

	public static void square(float x, float y, float radius, float rotation, float thick, Color color) {
		Lines.stroke(thick, Pal.gray);
		Lines.square(x, y, radius + 1f, rotation);
		Lines.stroke(thick / 3f, color);
		Lines.square(x, y, radius + 1f, rotation);
		Draw.reset();
	}

	public static void square(float x, float y, float radius, float thick, float rotation) {
		square(x, y, radius, rotation, thick, Pal.accent);
	}

	public static void square(float x, float y, float radius, float thick, Color color) {
		square(x, y, radius, 45, thick, color);
	}

	static final DrawEffect blur = new Blur();

	static ObjectMap<String, Seq<Runnable>> draws = new ObjectMap<>();
	public static void blur(Runnable draw) {
		if (!D_BLUR.getBool("enable", false)) return;
		// draws.get("blur", Seq::new).add(draw);
		blur.resize(graphics.getWidth(), graphics.getHeight());
		blur.capture();
		Draw.reset();
		draw.run();
		blur.render();
		Draw.flush();
	}
	/* static {
		topGroup.backDrawSeq.add(() -> {
			blur.resize();
			blur.capture();
			draws.get("blur", Seq::new).each(Runnable::run);
			blur.render();
			draws.get("blur").clear();
			return true;
		});
	} */

	public interface DrawEffect {
		void resize(int width, int height);

		void capture();

		void render();
	}
}
