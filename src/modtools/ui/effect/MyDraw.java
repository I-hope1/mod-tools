package modtools.ui.effect;

import arc.func.Floatc4;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.util.*;
import mindustry.graphics.Pal;
import modtools.events.E_Blur;
import modtools.ui.MyFonts;

import static arc.Core.graphics;

public class MyDraw {
	public static void dashLine(float thick, Color color, float x, float y, float x2, float y2, int segments) {
		Lines.stroke(thick);
		Draw.color(Pal.gray, color.a);
		Lines.dashLine(x, y, x2, y2, segments);
		Lines.stroke(thick / 1.7f, color);
		Lines.dashLine(x, y, x2, y2, segments);
		Draw.reset();
		//			Log.info(segments);
	}

	public static void dashLine(float thick, Color color, float x, float y, float x2, float y2) {
		dashLine(thick, color, x, y, x2, y2, (int) (Math.max(Math.abs(x - x2), Math.abs(y - y2)) / 5f));
	}

	public static void dashRect(float thick, Color color, float x, float y, float width, float height) {
		dashLine(thick, color, x + width, y, x + width, y + height);
		dashLine(thick, color, x, y + height, x, y);

		dashLine(thick, color, x, y, x + width, y);
		dashLine(thick, color, x + width, y + height, x, y + height);
	}

	public static void dashSquare(float thick, Color color, float x, float y, float size) {
		dashRect(thick, color, x - size / 2f, y - size / 2f, size, size);
	}

	public static void square(float x, float y, float radius, float rotation, float thick, Color color) {
		Lines.stroke(thick, Pal.gray);
		Lines.square(x, y, radius + 1f, rotation);
		Lines.stroke(thick / 1.7f, color);
		Lines.square(x, y, radius + 1f, rotation);
		Draw.reset();
	}

	public static void square(float x, float y, float radius, float thick, float rotation) {
		square(x, y, radius, rotation, thick, Pal.accent);
	}

	public static void square(float x, float y, float radius, float thick, Color color) {
		square(x, y, radius, 45, thick, color);
	}

	static final DrawEffect blur = new EBBlur();

	// static ObjectMap<String, Seq<Runnable>> draws = new ObjectMap<>();
	public static void blurRect(float x, float y, float w, float h) {
		if (!isBlurEnable()) return;
		// draws.get("blur", Seq::new).add(draw);
		blur.resize(graphics.getWidth(), graphics.getHeight());
		blur.capture(x, y, w, h);
		blur.render();
		Draw.flush();
	}
	public static boolean isBlurEnable() {
		return E_Blur.enabled.enabled();
	}


	public static final Vec2 vector = new Vec2();

	public static void dashCircle(float x, float y, float radius) {
		float scaleFactor = 0.3f;
		int   sides       = 10 + (int) (radius * scaleFactor);
		if (sides % 2 == 1) {
			++sides;
		}

		vector.set(0, 0);
		for (int i = 0; i < sides; i += 2) {
			float v = Time.globalTime / 16f;
			vector.set(radius, 0).rotate(360f / (float) sides * (i + v) + 90f);
			float x1 = vector.x;
			float y1 = vector.y;
			vector.set(radius, 0).rotate(360f / (float) sides * (i + 1 + v) + 90f);
			Lines.line(x1 + x, y1 + y, vector.x + x, vector.y + y);
		}
	}

	/* 左下角为（0,0） */
	/** 仿照{@link #dashCircle(float, float, float)} */
	public static void dashRect(float x, float y, float w, float h, float off) {
		float unit = (w + h) / 32f;
		float cx   = x - w / 2f, cy = y - h / 2f;
		Floatc4 line = (x1, y1, x2, y2) -> Lines.line(
		 cx + Mathf.clamp(x1, 0, w),
		 cy + Mathf.clamp(y1, 0, h),
		 cx + Mathf.clamp(x2, 0, w),
		 cy + Mathf.clamp(y2, 0, h)
		);

		float unit4 = unit * 4f;
		off %= unit4;
		float x1, y1;

		// top ("x: 0 -> w", y; h -> h)
		for (x1 = -unit4, y1 = h; x1 < w; x1 += unit4) {
			line.get(x1 + off, y1, x1 + off + unit, y1);
		}
		// right (x: w -> w; "y: h -> 0")
		off = x1 - unit4 + off - w;
		for (x1 = w, y1 = h; y1 > 0; y1 -= unit4) {
			line.get(x1, y1 - off, x1, y1 - off - unit);
		}
		// bottom ("x: w -> 0"; y: 0 -> 0)
		off = y1 + off;
		for (x1 = w, y1 = 0; x1 > 0; x1 -= unit4) {
			line.get(x1 - off, y1, x1 - off - unit, y1);
		}
		// left (x: 0 -> 0; "y: 0 -> h")
		off = x1 + unit4 + off;
		for (x1 = 0, y1 = -unit4; y1 < h; y1 += unit4) {
			line.get(x1, y1 + off, x1, y1 + off + unit);
		}
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
	public static void drawText(String text, float x, float y, Color color) {
		fontScaleDraw(() -> drawText(text, x, y, color, Align.center));
	}
	public static float fontHeight() {
		return font.getLineHeight() * fontScale;
	}
	public static Font  font      = MyFonts.def;
	public static float fontScale = 0.6f;
	public static void fontScaleDraw(Runnable draw) {
		float oldScaleX = font.getScaleX();
		float oldScaleY = font.getScaleY();
		font.getData().setScale(fontScale);
		Color oldColor = font.getColor();
		draw.run();
		font.setColor(oldColor);
		font.getData().setScale(oldScaleX, oldScaleY);
	}
	public static void drawText(String text, float x, float y, Color color, int align) {
		if (color.a == 0) return;
		font.setColor(color);
		font.draw(text, x, y, align);
	}

	public interface DrawEffect {
		void resize(int width, int height);

		void capture(float x, float y, float w, float h);

		void render();
	}
}
