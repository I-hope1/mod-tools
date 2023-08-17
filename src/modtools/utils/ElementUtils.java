package modtools.utils;

import arc.Core;
import arc.files.Fi;
import arc.func.Cons2;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.util.*;
import modtools.ui.effect.ScreenSampler;

import java.util.Optional;

import static mindustry.Vars.*;

public class ElementUtils {
	private static final Vec2 v1 = new Vec2();
	private static final Vec2 v2 = new Vec2();
	public static void addOrRemove(Element element, boolean show) {
		if (show) {
			Core.scene.add(element);
		} else {
			element.remove();
		}
	}
	public static Vec2 getAbsPosCenter(Element el) {
		return el.localToStageCoordinates(v2.set(el.getWidth() / 2f, el.getHeight() / 2f));
	}
	public static Vec2 getAbsPos(Element el) {
		if (true) return el.localToStageCoordinates(v1.set(0, 0));
		Vec2 vec2 = Tmp.v1.set(el.x, el.y);
		while (el.parent != null) {
			el = el.parent;
			vec2.add(el.x, el.y);
		}
		return vec2;
	}
	public static void quietScreenshot(Element element) {
		// ui.update();
		ScreenSampler.pause();
		JSFunc.dialog(screenshot(element, true, (region, pixmap) -> {
			Fi fi = screenshotDirectory.child(
			 Optional.ofNullable(element.name)
				.orElseGet(() -> "" + Time.nanos()) + ".png");
			PixmapIO.writePng(fi, pixmap);
			// pixmap.dispose();

			Core.app.post(() -> ui.showInfoFade(Core.bundle.format("screenshot", fi.path())));
		}));
		ScreenSampler._continue();
		// Time.runTask(30, w::hide);
	}
	public static TextureRegion screenshot(Element element, Cons2<TextureRegion, Pixmap> callback) {
		return screenshot(element, false, callback);
	}
	/** 使用ScreenUtils截图 */
	public static TextureRegion screenshot(Element el, boolean clear, Cons2<TextureRegion, Pixmap> callback) {
		int w = (int) el.getWidth(),
		 h = (int) el.getHeight();

		// 清空
		if (clear) {
			clearScreen();
			el.draw();
			Draw.flush();
		}
		Vec2   vec2   = getAbsPos(el);
		Pixmap pixmap = ScreenUtils.getFrameBufferPixmap((int) vec2.x, (int) vec2.y, w, h, true);

		TextureRegion textureRegion = new TextureRegion(new Texture(pixmap), 0, 0, w, h);
		if (callback != null) callback.get(textureRegion, pixmap);
		/* Core.scene.draw();
		Draw.flush(); */
		return textureRegion;
	}
	public static void clearScreen() {
		Gl.clearColor(0, 0, 0, 0);
		Gl.clear(Gl.colorBufferBit | Gl.depthBufferBit);
	}
}
