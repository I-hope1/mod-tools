package modtools.utils;

import arc.Core;
import arc.files.Fi;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.style.Drawable;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.ui.Styles;
import modtools.jsfunc.INFO_DIALOG;
import modtools.ui.*;
import modtools.ui.comp.Window;
import modtools.ui.content.ui.ShowUIList;
import modtools.ui.effect.ScreenSampler;

import java.util.Optional;

import static arc.Core.scene;
import static mindustry.Vars.*;
import static modtools.utils.ElementUtils.$.*;

public interface ElementUtils {
	static <T> T findParent(Element actor, Boolf<Element> condition) {
		while (true) {
			actor = actor.parent;
			if (condition.get(actor)) return (T) actor;
			if (actor == null) return null;
		}
	}
	static CharSequence getPath(Element element) {
		if (element == null) return "null";
		Element       el = element;
		StringBuilder sb = new StringBuilder();
		while (el != null) {
			if (el.name != null) {
				return STR."Core.scene.find(\"\{el.name}\")\{sb}";
			} else if (el instanceof Group && ShowUIList.uiKeyMap.containsKey(el)) {
				return STR."Vars.ui.\{ShowUIList.uiKeyMap.get(el)}\{sb}";
			} else {
				sb.append(".children.get(").append(el.getZIndex()).append(')');
				el = el.parent;
			}
		}
		return element.getScene() != null ? STR."Core.scene.root\{sb}" : sb.delete(0, 0);
	}

	class $ {
		static final Vec2 v1 = new Vec2();
		static final Vec2 v2 = new Vec2();
	}
	/** 存在就remove，不存在就add */
	static void addOrRemove(Element element, boolean show) {
		if (show) {
			Core.scene.add(element);
		} else {
			element.remove();
		}
	}
	static Vec2 getAbsPosCenter(Element el) {
		return el.localToStageCoordinates(v2.set(el.getWidth() / 2f, el.getHeight() / 2f));
	}

	static Vec2 getAbsolutePos(Element el) {
		return el.localToStageCoordinates(v1.set(0, 0));
	}
	static void quietScreenshot(Element element) {
		// ui.update();
		ScreenSampler.pause();
		INFO_DIALOG.dialog(screenshot(element, true, (region, pixmap) -> {
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
	static TextureRegion screenshot(Element element, Cons2<TextureRegion, Pixmap> callback) {
		return screenshot(element, false, callback);
	}
	/** 使用ScreenUtils截图 */
	static TextureRegion screenshot(Element el, boolean clear,
	                                Cons2<TextureRegion, Pixmap> callback) {
		int w = (int) el.getWidth(),
		 h = (int) el.getHeight();

		// 清空
		Vec2 vec = getAbsolutePos(el);
		if (clear) {
			clearScreen();
			float px = el.x, py = el.y;
			el.x = vec.x;
			el.y = vec.y;
			el.draw();
			el.x = px;
			el.y = py;

			Draw.flush();
		}
		Pixmap pixmap = ScreenUtils.getFrameBufferPixmap((int) vec.x, (int) vec.y, w, h, true);

		TextureRegion textureRegion = new TextureRegion(new Texture(pixmap), 0, 0, w, h);
		if (callback != null) callback.get(textureRegion, pixmap);
		/* Core.scene.draw();
		Draw.flush(); */
		return textureRegion;
	}
	static void clearScreen() {
		Gl.clearColor(0, 0, 0, 0);
		Gl.clear(Gl.colorBufferBit | Gl.depthBufferBit);
	}

	static @Nullable ScrollPane findClosestPane(Element actor) {
		return findParent(actor, e -> e instanceof ScrollPane);
	}
	static @Nullable Window findWindow(Element el) {
		return findParent(el, e -> e instanceof Window);
	}

	static String getSimpleName(Class<?> clazz) {
		while (clazz.getSimpleName().isEmpty() && clazz != Element.class) {
			clazz = clazz.getSuperclass();
		}
		return clazz.getSimpleName();
	}
	static String getElementName(Element element) {
		return element == scene.root ? "ROOT"
		 : STR."\{getSimpleName(element.getClass())}\{
		 element.name != null ? " ★" + element.name + "★" : ""
		 }";
	}


	/** 具有code的接口 */
	interface MarkedCode {
		int code();
		String name();
		default Drawable icon() {
			return null;
		}
	}

	static void addCodedBtn(
	 Table t, String text, int cols,
	 Intc cons, Intp prov, MarkedCode... seq) {
		t.button("", HopeStyles.flatt, null).with(tbtn -> {
			tbtn.clicked(() -> IntUI.showSelectTable(tbtn, (p, _, _) -> {
				buildModifier(p, cols, cons, prov, seq);
			}, false, Align.center));
			Table fill = tbtn.fill();
			fill.top().add(text, 0.6f).growX().labelAlign(Align.left).color(Color.lightGray);
			tbtn.getCell(fill).colspan(0);
			tbtn.getCells().reverse();
		}).size(85, 32).update(b -> b.setText(String.format("%X", (short) prov.get())));
	}

	private static void buildModifier(Table p, int cols, Intc cons, Intp prov, MarkedCode... seq) {
		p.button("All", HopeStyles.flatToggleMenut,
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
