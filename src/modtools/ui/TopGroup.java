package modtools.ui;

import arc.*;
import arc.func.Cons;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.*;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.actions.Actions;
import arc.scene.event.*;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Icon;
import mindustry.graphics.*;
import mindustry.ui.Styles;
import modtools.annotations.DataObjectInit;
import modtools.annotations.builder.DataBoolFieldInit;
import modtools.ui.IntUI.*;
import modtools.ui.components.*;
import modtools.ui.components.Window.DelayDisposable;
import modtools.ui.effect.*;
import modtools.utils.*;
import modtools.utils.array.TaskSet;

import java.util.*;

import static arc.Core.*;
import static modtools.IntVars.modName;
import static modtools.ui.Contents.*;
import static modtools.ui.IntUI.topGroup;
import static modtools.ui.components.Window.frontWindow;
import static modtools.utils.Tools.*;

// 存储mod的窗口和Frag
@DataObjectInit
public final class TopGroup extends WidgetGroup {
	@DataBoolFieldInit
	public boolean
	 checkUICount,
	 debugBounds,
	 selectInvisible;

	/* 渲染相关 */
	public BoolfDrawTasks drawSeq     = new BoolfDrawTasks();
	public BoolfDrawTasks backDrawSeq = new BoolfDrawTasks();

	public boolean      isSwitchWindows = false;
	public int          currentIndex    = 0;
	public List<Window> shownWindows    = new ArrayList<>();

	private Element selected;
	public Element getSelected() {
		return selected;
	}
	public boolean isSelecting() {
		return selecting;
	}
	private boolean selecting;
	private boolean cancelEvent;

	private final Group
	 back    = new NGroup("back"),
	 windows = new NGroup("windows"),
	 frag    = new NGroup("frag"),
	 others  = new NGroup("others") {
		 public void draw() {
			 // validate();
			 super.draw();
		 }
		 public Element hit(float x, float y, boolean touchable) {
			 return Sr(super.hit(x, y, touchable))
				.setOpt(children.contains(t -> t instanceof Window && t instanceof PopupWindow)
				 ? el -> or(el, this) : null)
				.get();
		 }
		 final Group menus = new NGroup(this, "menus");
		 final Group notices = new NGroup(this, "notices");
		 final Group _others = new NGroup(this, "others");
		 public void addChild(Element actor) {
			 if (_others == null) {
				 super.addChild(actor);
				 return;
			 }
			 (actor instanceof IMenu ? menus
				: actor instanceof INotice ? notices : _others)
				.addChild(actor);
		 }
	 };
	private final Table end = new MyEnd();

	public Element drawPadElem = null;
	public void setDrawPadElem(Element drawPadElem) {
		debugBounds = drawPadElem != null;
		this.drawPadElem = drawPadElem;
	}
	public void draw() {
		setTransform(false);
		backDrawSeq.exec();
		drawResidentTasks.each(ResidentDrawTask::backDraw);
		super.draw();

		if (elementDrawer != null && selected != null) {
			// Draw.flush();
			elementDrawer.draw(selecting, selected);
		}
		drawResidentTasks.each(ResidentDrawTask::elemDraw);

		Draw.flush();

		drawSeq.exec();
		drawResidentTasks.each(ResidentDrawTask::endDraw);
		Draw.flush();

		if (!debugBounds && drawPadElem == null) return;
		Element drawPadElem = or(this.drawPadElem, scene.root);
		Vec2    vec2;
		if (drawPadElem.parent != null) {
			vec2 = ElementUtils.getAbsolutePos(drawPadElem.parent);
		} else if (drawPadElem == scene.root) {
			vec2 = Tmp.v1.set(0, 0);
		} else return;
		Draw.color(Color.white);
		Draw.alpha(0.7f);
		ScreenSampler.pause();
		drawPad(drawPadElem, vec2);
		ScreenSampler._continue();
	}
	/** 如果选中的元素太小，会在边缘显示 */
	private void drawSlightlyIfSmall() {
		if (selected == null || selected.getWidth() > width / 3f || selected.getHeight() > height / 3f) return;

		Vec2    mouse = input.mouse();
		boolean right = mouse.x < width / 2f;
		Tmp.v1.x = right ? width - selected.getWidth() : 0;
		boolean top = mouse.y < height / 2f;
		Tmp.v1.y = top ? height - selected.getHeight() : 0;
		// Tools.screenshot(selected, true, null).texture;
		// buffer.blit(MyShaders.Specl);
		// scene.getCamera().bounds(Tmp.r1.set(selected.x, selected.y, selected.getWidth(), selected.getHeight()));
		Draw.rect(Draw.wrap(ScreenSampler.bufferCapture(selected)),
		 Tmp.v1.x + selected.getWidth() / 2f,
		 Tmp.v1.y + selected.getHeight() / 2f,
		 selected.getWidth(),
		 /* 上下翻转 */-selected.getHeight());
		Gl.flush();
		Draw.color(ColorFul.color);
		Lines.stroke(4f);
		Lines.line(mouse.x, mouse.y,
		 (right ? 0 : selected.getWidth()) + Tmp.v1.x,
		 (top ? 0 : selected.getHeight()) + Tmp.v1.y);
	}


	@DataBoolFieldInit
	public static boolean drawHiddenPad;


	static final Mat mat = new Mat();
	public static void drawPad(Element elem, Vec2 vec2) {
		if (!drawHiddenPad && !elem.visible) return;
		/* translation也得参与计算 */
		elem.localToParentCoordinates(vec2);

		float thick = elem instanceof Group ? 2 : 1;
		Draw.color(elem instanceof Group ? Color.sky : Color.green, 0.9f);
		Lines.stroke(thick);
		Drawf.dashRectBasic(vec2.x, vec2.y - thick,
		 clamp(vec2, elem.getWidth() + thick, true),
		 clamp(vec2, elem.getHeight() + thick, false));
		/* Lines.stroke(elem instanceof Group ? 3 : 1);
		Draw.color(elem instanceof Group ? Color.sky : Color.green, 0.9f);
		Lines.rect(vec2.x, vec2.y,
		 elem.getWidth(), elem.getHeight()); */

		// Draw.color(Color.white, 0.01f);
		// Fill.crect(vec2.x, vec2.y, elem.getWidth(), elem.getHeight());
		if (elem instanceof Table) {
			review_element.drawMargin(vec2, (Table) elem);
		}
		if (elem.parent instanceof Table) {
			review_element.drawPadding(elem, vec2, (Table) elem.parent);
		}

		if (elem instanceof Group group) {
			float x = vec2.x, y = vec2.y;
			for (var e : group.getChildren()) {
				drawPad(e, vec2.set(x, y));
			}
		}
	}
	public static float clamp(Vec2 pos, float value, boolean x) {
		return Mathf.clamp(
		 value, 0, x ? pos.x + graphics.getWidth() : pos.y + graphics.getHeight()
		);
	}

	public TopGroup() {
		if (topGroup != null) throw new IllegalStateException("topGroup已被加载");
		addSceneListener();
		scene.addListener(new SwitchInputListener());
		scene.addCaptureListener(new SwitchGestureListener());
		if (OS.isWindows) scene.addListener(new CloseWindowListener());

		fillParent = true;
		touchable = Touchable.childrenOnly;
		name = modName + "-TopGroup";

		scene.add(this);
		Group[] all = {back, windows, frag, others, new FillEnd()};
		for (Group group : all) {
			group.fillParent = true;
			group.touchable = Touchable.childrenOnly;
			super.addChild(group);
		}
		// update(this::toFront);

		TASKS.add(() -> {
			toFront();
			if (checkUICount) {
				if (scene.root.getChildren().count(el -> el.visible) > 70) {
					tester.loop = false;
					Dialog dialog;
					while (true) {
						dialog = scene.getDialog();
						if (dialog == null) break;
						dialog.hide();
					}
				}
				if (windows.getChildren().count(el -> el.visible) > 70) {
					windows.getChildren().<Window>as().each(Window::hide);
				}

			}
		});

		ScreenSampler.init();

		update(() -> {
			// Log.info(selected);
			if (selecting) {
				scene.setKeyboardFocus(topGroup);
			}
			if (shownWindows.isEmpty()) {
				resetSwitch();
			}
			end.visible = isSwitchWindows;
			end.touchable = isSwitchWindows ? Touchable.childrenOnly : Touchable.disabled;
		});
	}
	public List<Window> acquireShownWindows() {
		shownWindows.clear();
		Cons<Element> cons = elem -> {
			if (elem instanceof Window window && !(elem instanceof DelayDisposable)) {
				shownWindows.add(window);
			}
		};
		windows.forEach(cons);
		others.forEach(cons);
		return shownWindows;
	}

	public static final boolean enabled = true;

	/**
	 * 根据类别添加child
	 * <p>按顺序z-index不断增加</p>
	 * <table style="border: 1px solid #ccf; background: rgba(0,0,0,0); color: #ffc">
	 *     <thead>
	 *     <tr>
	 *         <th scope="col">元素类型</th>
	 *         <th scope="col">所处的父节点</th>
	 *     <tr/>
	 *     </thead>
	 *     <tbody>
	 *     <tr>
	 *         <td>BackInterface</td>
	 *         <td>back</td>
	 *     </tr>
	 *     <tr>
	 *         <td>Window</td>
	 *         <td>windows</td>
	 *     </tr>
	 *     <tr>
	 *         <td>Frag</td>
	 *         <td>frag</td>
	 *     </tr>
	 *     <tr>
	 *         <td>其余</td>
	 *         <td>others</td>
	 *     </tr>
	 *     </tbody>
	 *  </table>
	 */
	public void addChild(Element actor) {
		if (enabled) {
			(actor instanceof BackInterface ? back
			 : actor instanceof PopupWindow ? others
			 : actor instanceof Window ? windows
			 : actor instanceof Frag ? frag
			 : others).addChild(actor);
			return;
		}
		scene.add(actor);
	}

	public Element hit(float x, float y, boolean touchable) {
		/*isSwicthWindows ? end.hit(x, y, touchable) : */
		return Sr(super.hit(x, y, touchable))
		 .setnull(el -> el == this || el == null || el.touchable == Touchable.disabled)
		 .get();
	}

/* 	public void requestSelectRegion
			(TouchDown touchDown,
			 TouchDragged touchDragged,
			 TouchUp touchUp) {

	} */

	/**
	 * 请求选择元素
	 * @param drawer 用于选择时渲染
	 */
	public void requestSelectElem(Drawer drawer, Cons<Element> callback) {
		if (callback == null) throw new IllegalArgumentException("callback is null");
		selected = null;
		selecting = !selecting;
		if (selecting) {
			scene.unfocusAll();
			elementDrawer = drawer;
			elementCallback = callback;
		} else {
			resetSelectElem();
		}
	}

	/* 过滤掉的选择元素 */
	public ObjectFloatMap<Element> filterSelected = new ObjectFloatMap<>();
	Element getSelected0(float x, float y) {
		return scene.root.hit(x, y, !selectInvisible);
	}
	// 获取指定位置的元素
	public void getSelected(float x, float y) {
		selected = getSelected0(x, y);
	}

	public static final ObjectSet<Element>  searchBlackList = new ObjectSet<>();
	public static final ObjectSet<Class<?>> classBlackList  = new ObjectSet<>();

	public              Cons<Element> elementCallback = null;
	public              Drawer        elementDrawer   = null;
	public static final Drawer        defaultDrawer   = (selecting, el) -> {
		if (!selecting) return;
		TopGroup.drawFocus(el, ElementUtils.getAbsolutePos(el), IntUI.DEF_FOCUS_COLOR);
	};

	/** 用于获取元素 */
	private void addSceneListener() {
		scene.root.getCaptureListeners().insert(0, new InputListener() {
			boolean locked = false;
			final Element mask = new Hitter() {
				public Element hit(float x, float y, boolean touchable) {
					return cancelEvent ? this : null;
				}
			};
			public void cancel() {
				selecting = false;
				resetSelectElem();
			}

			/* 拦截keydown */
			public boolean keyDown(InputEvent event, KeyCode keycode) {
				if (!selecting) return true;
				if (keycode == KeyCode.escape) {
					event.cancel();
					cancel();
				}
				if (keycode == KeyCode.f && selected != null) {
					filterElem(selected);
					// Log.info(selected);
				}
				/* 拦截事件 */
				HopeInput.pressed.clear();
				HopeInput.justPressed.clear();
				event.stop();
				return false;
			}
			private void filterElem(Element element) {
				element.visible = false;
				filterSelected.put(element, element.translation.x);
				element.translation.x = scene.getWidth() * 2;
			}
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (locked) {
					if (Vars.android) {
						filterElem(getSelected0(x, y));
					}
					return false;
				}
				if (event.listenerActor.isDescendantOf(searchBlackList::contains)) return false;
				if (!selecting) return false;
				locked = true;
				topGroup.addChild(mask);
				cancelEvent = true;
				event.cancel();

				cancelEvent = false;
				// frag.touchable = Touchable.disabled;
				getSelected(x, y);
				cancelEvent = true;
				return true;
			}
			public void touchDragged(InputEvent event, float x, float y, int pointer) {
				cancelEvent = false;
				getSelected(x, y);
				cancelEvent = true;
			}
			public boolean filter() {
				if (selected == null) return false;
				Element  parent = selected;
				Class<?> cl;
				while (parent != null) {
					cl = parent.getClass();
					for (Class<?> bcl : classBlackList) {
						if (bcl.isAssignableFrom(cl)) return false;
					}
					parent = parent.parent;
				}
				return true;
			}

			public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
				locked = false;
				mask.remove();
				filterSelected.each(entry -> {
					entry.key.translation.x = entry.value;
					entry.key.visible = true;
				});
				filterSelected.clear();

				selecting = false;
				if (elementCallback != null && filter()) elementCallback.get(selected);
				resetSelectElem();
			}
		});
	}
	private void resetSelectElem() {
		selected = null;
		elementDrawer = null;
		elementCallback = null;
	}


	public final Seq<ResidentDrawTask> drawResidentTasks = new Seq<>();

	{
		Events.run(Trigger.uiDrawBegin, () -> drawResidentTasks.each(ResidentDrawTask::init));
		Events.run(Trigger.uiDrawEnd, () -> drawResidentTasks.each(ResidentDrawTask::afterAll));
	}

	public void focusOnElement(FocusTask task) {
		drawResidentTasks.add(task);
	}
	public void removeFocusElement(FocusTask task) {
		drawResidentTasks.remove(task);
	}


	/**
	 * just a flag
	 * @see modtools.ui.TopGroup#addChild
	 */
	public static class BackElement extends Element implements BackInterface {}

	public interface BackInterface {}


	public interface Drawer {
		void draw(boolean selecting, Element selected);
	}


	boolean KAL_once = false;

	/**
	 * 用于切换窗口的事件侦听器
	 * @see SwitchInputListener
	 */
	private class SwitchGestureListener extends ElementGestureListener {
		int lastTouches;
		public void touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
			lastTouches = input.getTouches();
		}
		public void fling(InputEvent event, float velocityX, float velocityY, KeyCode button) {
			// Log.info("fling: (@, @, @) ", velocityX, velocityY, lastTouches);
			if (velocityX < 1000 || lastTouches != 3) return;
			Log.info("valid");
			if (!isSwitchWindows) {
				currentIndex = frontWindow != null ? shownWindows.indexOf(frontWindow) : 0;
			}
			isSwitchWindows = true;
			if (!KAL_once) {
				resolveOnce();
			}
		}
	}
	/**
	 * 用于切换窗口的事件侦听器
	 * @see SwitchGestureListener
	 */
	private class SwitchInputListener extends InputListener {
		public boolean keyDown(InputEvent event, KeyCode keycode) {
			// acquireShownWindows();
			if (shownWindows.isEmpty()) return false;
			if ((keycode == KeyCode.tab && Core.input.ctrl()) /* || (Vars.mobile && keycode == KeyCode.volumeDown) */) {
				if (!KAL_once) {
					resolveOnce();
				}
				scene.setKeyboardFocus(TopGroup.this);
				if (!isSwitchWindows) {
					currentIndex = frontWindow != null ? shownWindows.indexOf(frontWindow) : 0;
				}
				if (shownWindows.size() > 1) currentIndex += Core.input.shift() ? -1 : 1;
				if (currentIndex < 0) {
					currentIndex += shownWindows.size();
				} else if (currentIndex >= shownWindows.size()) {
					currentIndex -= shownWindows.size();
				}
				isSwitchWindows = true;
			}
			return true;
			/*var children = Window.focusWindow.parent.getChildren();
			children.get(Window.focusWindow.getZIndex() + 1 % children.size).toFront();*/
		}

		public boolean keyUp(InputEvent event, KeyCode keycode) {
			if (event.cancelled) return false;
			if (shownWindows.isEmpty()) return false;
			if (isSwitchWindows && !Core.input.ctrl()) {
				resolveSwitch();
			}
			return true;
		}
	}

	private class CloseWindowListener extends InputListener {
		public boolean keyDown(InputEvent event, KeyCode keycode) {
			if (keycode == KeyCode.f4 && input.shift() && shownWindows.size() > 0) frontWindow.hide();
			return super.keyDown(event, keycode);
		}
	}
	private void resolveSwitch() {
		resetSwitch();
		if (currentIndex < shownWindows.size()) shownWindows.get(currentIndex).toFront();
	}
	private void resetSwitch() {
		isSwitchWindows = false;
		KAL_once = false;
	}


	public class GroupHitter extends Hitter {
		{
			touchablility = () -> isSwitchWindows ? Touchable.enabled : Touchable.disabled;
			clicked(TopGroup.this::resetSwitch);
		}
	}
	private void resolveOnce() {
		KAL_once = true;
		end.clearChildren();
		Table paneTable = new Table();
		end.pane(paneTable).grow().with(
		 s -> s.setScrollingDisabled(OS.isWindows, false));
		int             W          = graphics.getWidth(), H = graphics.getHeight();
		float           eachW      = W > H ? W / 3f : W / 4f - 16f, eachH = H / 3f;
		final Element[] tappedElem = {null}, hoveredElem = {null};
		Table           table      = paneTable.row().table().get();
		for (Window w : shownWindows) {
			Image el = new Image(w.screenshot());

			// TextureRegionDrawable drawable = new TextureRegionDrawable((TextureRegionDrawable) Tex.pane);
			boolean[] cancelEvent = {false};
			float     bestScl     = eachW / eachH, realScl = el.getWidth() / el.getHeight();
			float     width1      = bestScl < realScl ? eachW : eachH * realScl;
			if (table.getPrefWidth() + Scl.scl(width1) > width - 30) table.row();
			table.button(t -> {
				t.margin(4, 6, 4, 6);
				t.act(0);
				t.tapped(() -> tappedElem[0] = t);
				t.hovered(() -> hoveredElem[0] = t);
				t.exited(() -> hoveredElem[0] = null);
				t.released(() -> tappedElem[0] = null);
				t.table(t1 -> {
					t1.add(w.title.getText(), Pal.accent).left().growX();
					t1.button(Icon.cancelSmall, Styles.clearNonei, () -> {
						w.hide();
						cancelEvent[0] = true;
					}).visible(() -> hoveredElem[0] == t);
				}).growX().pad(6, 8, 6, 8).row();
				t.add(el).size(width1,
				 bestScl < realScl ? eachW / realScl : eachH);
				t.clicked(() -> {
					if (cancelEvent[0]) {
						removeWindow(hoveredElem[0]);
						return;
					}
					currentIndex = t.getZIndex();
					resolveSwitch();
				});
			}, Styles.flatToggleMenut, () -> {}).pad(6, 8, 6, 8).update(t -> {
				if (!w.isShown()) {
					removeWindow(t);
				}
				t.setChecked(tappedElem[0] == null ? t.getZIndex() == currentIndex : tappedElem[0] == t);
			});
		}
	}
	private void removeWindow(Element el) {
		el.actions(Actions.fadeOut(0.2f, Interp.fade), Actions.remove());
		currentIndex = 0;
	}
	private static class MyEnd extends Table {
		// final FrameBuffer buffer = new FrameBuffer();

		Pixmap  pixmap;
		Texture texture;

		{
			margin(10, 12, 10, 12);
			name = "end";
			/*
			allocateNewTexture();
			Events.on(EventType.ResizeEvent.class, e -> {
				allocateNewTexture();
			}); */
		}

		private Texture getSampler() {
			int w = (int) getPrefWidth(), h = (int) getPrefHeight();
			drawBack();
			texture.draw(pixmap);
			Gl.pixelStorei(Gl.packAlignment, 1);
			Gl.readPixels((int) x, (int) y, w, h, Gl.rgba, Gl.unsignedByte, pixmap.pixels);
			Pixmap in = ScreenUtils.getFrameBufferPixmap((int) x, (int) y, w, h);
			texture.draw(in, (int) x, (int) y);
			in.dispose();
			return texture;
		}
		public void drawBack() {
			Draw.color(0x00000099);
			float ow = 20, oh = 12;
			Fill.crect(x - ow / 2f, y - oh / 2f, getPrefWidth() + ow, getPrefHeight() + oh);
		}
		private void allocateNewTexture() {
			if (pixmap != null) pixmap.dispose();
			texture = new Texture(pixmap = new Pixmap(
			 graphics.getWidth(), graphics.getHeight()
			));
		}


		public void draw() {
			MyDraw.blurRect(x, y, width, height);
			drawBack();
			super.draw();
		}
	}

	public static class BoolfDrawTasks extends TaskSet {}

	public interface ResidentDrawTask {
		default void backDraw() {}

		default void elemDraw() {}
		/**
		 * 在{@code drawer}渲染之前渲染
		 * @param drawer 等一会会渲染的元素
		 */
		default void beforeDraw(Window drawer) {}
		default void endDraw() {}
		default void init() {}
		default void afterAll() {}
	}

	public static class NGroup extends Group {
		public NGroup(String name) {
			super();
			this.name = name;
			update(this::validate);
		}
		public NGroup(Group parent, String name) {
			this(name);
			touchable = Touchable.childrenOnly;
			setFillParent(true);
			parent.addChild(this);
		}
	}
	public static class FocusTask implements ResidentDrawTask {
		public       Element elem;
		public final Color   maskColor, focusColor;
		public boolean drawSlightly, transition;
		public FocusTask(Color maskColor, Color focusColor) {
			this.maskColor = maskColor;
			this.focusColor = focusColor;
		}
		public FocusTask(Element elem, Color maskColor, Color focusColor) {
			this.elem = elem;
			this.maskColor = maskColor;
			this.focusColor = focusColor;
		}
		public void elemDraw() {
			drawFocus(elem);
		}
		public void drawFocus(Element elem) {
			drawFocus(elem, ElementUtils.getAbsolutePos(elem));
		}
		public void drawFocus(Element elem, Vec2 vec2) {
			TopGroup.drawFocus(elem, vec2, focusColor);
		}
		public void endDraw() {
			if (maskColor.a == 0) return;

			Draw.color(maskColor);
			Fill.crect(0, 0, Core.graphics.getWidth(), Core.graphics.getHeight());

			if (drawSlightly) topGroup.drawSlightlyIfSmall();
		}
	}
	public static void drawFocus(Element elem, Vec2 vec2, Color focusColor) {
		Gl.flush();
		if (focusColor.a > 0) {
			float alpha = focusColor.a * (elem.visible ? 1 : 0.6f);
			Draw.color(focusColor, alpha);
			// Tmp.m1.set(Draw.trans());
			Fill.crect(vec2.x, vec2.y, elem.getWidth(), elem.getHeight());
		}
		if (!elem.visible) {
			Draw.color(Pal.accent);
			TextureRegionDrawable icon = Icon.eyeOffSmall;
			icon.draw(vec2.x, vec2.y, 16, 16);
		}

		Draw.color(Pal.accent);
		float thick = 1f;
		Lines.stroke(thick);
		Drawf.dashRectBasic(vec2.x, vec2.y - thick, elem.getWidth() + thick, elem.getHeight() + thick);
	}
	private class FillEnd extends Table {
		public FillEnd() {
			super(t -> {
				t.addChild(new GroupHitter());
				t.add(TopGroup.this.end);
			});
		}
		public Element hit(float x, float y, boolean touchable) {
			return or(super.hit(x, y, touchable), isSwitchWindows ? this : null);
		}
	}

}
