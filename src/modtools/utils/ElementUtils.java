package modtools.utils;

import arc.Core;
import arc.files.Fi;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.style.Style;
import arc.scene.ui.ScrollPane;
import arc.util.*;
import modtools.content.ui.ShowUIList;
import modtools.jsfunc.INFO_DIALOG;
import modtools.ui.comp.Window;
import modtools.ui.effect.*;
import modtools.utils.ui.LerpFun.DrawExecutor;

import java.util.Optional;

import static arc.Core.graphics;
import static mindustry.Vars.*;
import static modtools.ui.IntUI.topGroup;
import static modtools.utils.ElementUtils.$.*;

public interface ElementUtils {
	static <T> T findParent(Element actor, Boolf<Element> condition) {
		while (true) {
			if (actor == null) return null;
			if (condition.get(actor)) return (T) actor;
			actor = actor.parent;
		}
	}
	static <T> T findParent(Element actor, Class<T> clazz) {
		return findParent(actor, clazz::isInstance);
	}


	static Style getStyle(Element element) {
		try {
			return (Style) element.getClass().getMethod("getStyle", (Class<?>[]) null).invoke(element, (Object[]) null);
		} catch (Throwable e) {
			return null;
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
	static void scrollTo(Element actor, Element target) {
		ScrollPane pane = findClosestPane(actor);
		Time.runTask(40, () -> HopeFx.changedFx(target));
		pane.scrollTo(0, target.localToAscendantCoordinates(pane.getWidget(),
			Tmp.v1.set(target.getWidth() / 2, target.getHeight() / 2)).y,
		 target.getWidth(), target.getHeight(),
		 false, true);
	}
	static boolean checkInStage(Vec2 pos) {
		return Tmp.r1.set(0, 0, graphics.getWidth(), graphics.getHeight()).contains(pos);
	}
	static void hideBarIfValid(ScrollPane pane) {
		pane.setScrollingDisabled(true, false);
		pane.setOverscroll(false, false);
		pane.update(() -> {
			pane.setScrollingDisabled(true, pane.getHeight() == pane.getPrefHeight());
		});
	}

	class $ {
		static final Vec2 v1 = new Vec2();
		static final Vec2 v2 = new Vec2();
	}
	/** 存在就remove，不存在就add */
	static void addOrRemove(Element element, boolean show) {
		if (show) {
			topGroup.addChild(element);
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

	static @Nullable DrawExecutor findDrawExecutor(Element actor) {
		return findParent(actor, DrawExecutor.class);
	}
	static @Nullable ScrollPane findClosestPane(Element actor) {
		return findParent(actor, ScrollPane.class);
	}
	static @Nullable Window findWindow(Element el) {
		return findParent(el, Window.class);
	}

}
