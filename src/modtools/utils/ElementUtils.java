package modtools.utils;

import arc.Core;
import arc.files.Fi;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.style.Style;
import arc.scene.ui.Label;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextButton;
import arc.struct.Seq;
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
import static modtools.utils.ElementUtils.ZL.*;

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

	static boolean checkIn(Element actor, float x, float y) {
		return checkIn(actor, actor.getScene().root, x, y);
	}
	static boolean checkIn(Element actor, Group group, float x, float y) {
		if (actor == null) return false;
		if (group == null) return false;
		group.localToDescendantCoordinates(actor, Tmp.v1.set(x, y));
		return Tmp.v1.x > 0 && Tmp.v1.y > 0 && Tmp.v1.x < actor.getWidth() && Tmp.v1.y < actor.getHeight();
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
				return "Core.scene.find(\"" + el.name + "\")" + sb;
			} else if (el instanceof Group && ShowUIList.uiKeyMap.containsKey(el)) {
				return "Vars.ui." + ShowUIList.uiKeyMap.get(el) + sb;
			} else {
				sb.append(".children.get(").append(el.getZIndex()).append(')');
				el = el.parent;
			}
		}
		return element.getScene() != null ? "Core.scene.root" + sb : sb.delete(0, 0);
	}
	/**
	 * 人类可读的元素路径（从 scene.root 往下），用于在日志/对话里指认某个元素，
	 * 例如 {@code Window#0 > Table#1 > Label:"ReviewElement"}。
	 * <p>与 {@link #getPath(Element)} 的区别：那个产出可直接执行的 Java 表达式，这个只求一眼能看懂。
	 */
	static CharSequence getDisplayPath(Element element) {
		if (element == null) return "null";
		Seq<String> parts = new Seq<>();
		for (Element el = element; el != null; el = el.parent) {
			StringBuilder sb = new StringBuilder(32).append(el.getClass().getSimpleName());
			// root 没有兄弟，索引无意义
			if (el.parent != null) sb.append('#').append(el.getZIndex());
			if (el.name != null) sb.append('"').append(el.name).append('"');
			CharSequence text = el instanceof Label l ? l.getText() :
			 el instanceof TextButton tb ? tb.getText() : null;
			if (text != null && text.length() > 0) {
				sb.append(":\"");
				// 文本可能很长（整段说明文字），只留开头够定位就行
				sb.append(text.length() > 16 ? text.subSequence(0, 16) + "…" : text);
				sb.append('"');
			}
			parts.add(sb.toString());
		}
		return parts.reverse().toString(" > ");
	}

	/** 把 {@code target} 平滑滚到最近一层 {@link ScrollPane} 的正中间，滑到位后再闪一下。 */
	static void scrollTo(Element actor, Element target) {
		if (target == null) return;
		ScrollPane pane = findClosestPane(actor);
		if (pane == null) return;
		Element widget = pane.getWidget();
		if (widget == null || !target.isDescendantOf(widget)) return;

		pane.validate();

		float w = target.getWidth(), h = target.getHeight();
		pane.scrollTo(0, target.localToAscendantCoordinates(widget,
			Tmp.v1.set(w / 2, h / 2)).y,
		 w, h,
		 false, true);

		flashWhenScrolled(pane, target, Math.max(h, 40f), 60);
	}

	/**
	 * 平滑滚动的耗时取决于滚动距离，必须等视觉位置真的接近目标再闪，否则距离一远，
	 * 元素还没滑进屏幕闪光就结束了。
	 *
	 * @param tolerance 允许的视觉/逻辑滚动量差
	 * @param tries     最多再等多少帧
	 */
	static void flashWhenScrolled(ScrollPane pane, Element target, float tolerance, int tries) {
		if (target.getScene() != null
		    && Mathf.equal(pane.getVisualScrollY(), pane.getScrollY(), tolerance)) {
			HopeFx.changedFx(target);
		} else if (tries > 0) {
			Time.runTask(1f, () -> flashWhenScrolled(pane, target, tolerance, tries - 1));
		}
	}
	static boolean checkInStage(Vec2 pos) {
		return Tmp.r1.set(0, 0, graphics.getWidth(), graphics.getHeight()).contains(pos);
	}
	static void hideBarIfValid(ScrollPane pane) {
		pane.setScrollingDisabled(true, false);
		pane.setOverscroll(false, false);
		pane.update(() -> {
			pane.setScrollingDisabled(true, Mathf.equal(pane.getHeight(), pane.getPrefHeight()));
		});
	}

	class ZL {
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
		return el.localToStageCoordinates(v2.set(el.getX(Align.center) - el.x, el.getY(Align.center) - el.y));
	}

	static Vec2 getAbsolutePos(Element el) {
		return el.localToStageCoordinates(v1.set(0, 0));
	}
	Vec2 topLeft = new Vec2();
	static Vec2 getAbsolutePosTopLeft(Element el) {
		return el.localToStageCoordinates(topLeft.set(0, 0));
	}
	static void quietScreenshot(Element element) {
		// ui.update();
		INFO_DIALOG.dialog(screenshot(element, true, (region, pixmap) -> {
			Fi fi = screenshotDirectory.child(
			 Optional.ofNullable(element.name)
				.orElseGet(() -> "" + Time.nanos()) + ".png");
			PixmapIO.writePng(fi, pixmap);
			// pixmap.dispose();

			Core.app.post(() -> ui.showInfoFade(Core.bundle.format("screenshot", fi.path())));
		}));
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
