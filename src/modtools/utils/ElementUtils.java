package modtools.utils;

import arc.Core;
import arc.files.Fi;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.style.Drawable;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.*;
import mindustry.ui.Styles;
import modtools.ui.IntUI;
import modtools.ui.components.*;
import modtools.ui.components.utils.ValueLabel;
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

	public static Vec2 getAbsolutePos(Element el) {
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
		Core.app.post(() -> {
			JSFunc.dialog(screenshot(element, true, (region, pixmap) -> {
				Fi fi = screenshotDirectory.child(
				 Optional.ofNullable(element.name)
					.orElseGet(() -> "" + Time.nanos()) + ".png");
				PixmapIO.writePng(fi, pixmap);
				// pixmap.dispose();

				Core.app.post(() -> ui.showInfoFade(Core.bundle.format("screenshot", fi.path())));
			}));
		});
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
			// boolean last = false;
			el.draw();
		}
		Vec2 vec2 = getAbsolutePos(el);
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

	public static @Nullable Window getWindow(ValueLabel valueLabel) {
		Element el = valueLabel;
		while (el != null) {
			el = el.parent;
			if (el instanceof Window window) return window;
		}
		return null;
	}

	/** 具有code的接口 */
	public interface MarkedCode {
		int code();
		String name();
		default Drawable icon() {
			return null;
		}
	}

	public static void addCodedBtn(
	 Table t, String text, int cols,
	 Intc cons, Intp prov, MarkedCode... seq) {
		t.button("", Styles.flatt, null).with(tbtn -> {
			tbtn.clicked(() -> IntUI.showSelectTable(tbtn, (p, hide, ___) -> {
				buildModifier(p, cols, cons, prov, seq);
			}, false, Align.center));
			Table fill = tbtn.fill();
			fill.top().add(text, 0.6f).growX().labelAlign(Align.left).color(Color.lightGray);
			tbtn.getCell(fill).colspan(0);
			tbtn.getCells().reverse();
		}).size(85, 32).update(b -> b.setText(String.format("%X", (short) prov.get())));
	}

	private static void buildModifier(Table p, int cols, Intc cons, Intp prov, MarkedCode... seq) {
		p.button("all", Styles.flatToggleMenut,
			() -> cons.get(prov.get() != -1 ? -1 : 0))
		 .growX().colspan(4).height(42)
		 .update(b -> b.setChecked(prov.get() == -1))
		 .row();
		int c = 0;
		for (var value : seq) {
			int      bit      = 1 << value.code();
			Runnable runnable = () -> cons.get(prov.get() ^ bit);
			Drawable icon     = value.icon();
			(icon == null ? p.button(value.name(), Styles.flatToggleMenut, runnable)
			 : p.button(value.name(), value.icon(), Styles.flatToggleMenut, 24, runnable))
			 .size(120, 42)
			 .update(b -> b.setChecked((prov.get() & bit) != 0));
			if (++c % cols == 0) p.row();
		}
	}

}
