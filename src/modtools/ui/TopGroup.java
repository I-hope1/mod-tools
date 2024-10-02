package modtools.ui;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.*;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.actions.Actions;
import arc.scene.event.EventListener;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.annotations.settings.*;
import modtools.content.ui.ReviewElement;
import modtools.events.ISettings;
import modtools.struct.TaskSet;
import modtools.ui.comp.*;
import modtools.ui.comp.Window.DelayDisposable;
import modtools.ui.control.*;
import modtools.ui.effect.*;
import modtools.utils.*;
import modtools.utils.ui.ColorFul;

import java.util.*;
import java.util.function.Consumer;

import static arc.Core.*;
import static modtools.content.ui.ReviewElement.Settings.checkCullingArea;
import static modtools.ui.Contents.tester;
import static modtools.ui.IntUI.topGroup;
import static modtools.ui.TopGroup.TSettings.*;
import static modtools.ui.comp.Window.frontWindow;
import static modtools.unsupported.HopeProcessor.NPX;
import static modtools.utils.Tools.*;

// 存储mod的窗口和Frag
public final class TopGroup extends WidgetGroup implements Disposable {
	@SettingsInit
	public enum TSettings implements ISettings {
		checkUICount,
		/** @see ISettings#$(int, int, int, int) */
		@Switch(dependency = "checkUICount")
		maxUICount(int.class, it -> it.$(70, 10, 200, 1)),
		selectInvisible,

		debugBounds,
		@Switch(dependency = "debugBounds")
		drawHiddenPad,
		/** @see ISettings#$(Drawable, Cons) */
		paneDrawable(Drawable.class, it -> it.$(Tex.pane, d -> Window.myPane.reset(d, Color.white))),
		;
		// overrideScene
		TSettings() { }
		TSettings(Class<?> type, Cons<ISettings> builder) { }
	}

	/* 渲染相关 */
	public BoolfDrawTasks drawSeq     = new BoolfDrawTasks();
	public BoolfDrawTasks backDrawSeq = new BoolfDrawTasks();

	/**
	 * 按zIndex排列
	 * @see #acquireShownWindows()
	 */
	public Seq<Window> shownWindows = new Seq<>();

	private Element selected;
	public Element getSelected() {
		return selected;
	}
	public boolean isSelecting() {
		return isSelecting;
	}
	private boolean isSelecting;

	// ------

	private final Group
	 back    = new NGroup("back"),
	 windows = new NGroup("windows"),
	 frag    = new NGroup("frag"),
	 infos   = new NGroup("infos");
	public interface IInfo { }
	final SwitchView switchView = new SwitchView();


	private static Element drawPadElem = null;
	public static void setDrawPadElem(Element drawPadElem) {
		debugBounds.set(drawPadElem != null);
		TopGroup.drawPadElem = drawPadElem;
	}
	public static Element getDrawPadElem() { return drawPadElem; }

	public void draw() {
		setTransform(false);
		if (elementDrawer != null) elementDrawer.drawMask();

		backDrawSeq.exec();
		drawTask(-1, ResidentDrawTask::backDraw);
		super.draw();

		if (elementDrawer != null && selected != null) {
			// Draw.flush();
			elementDrawer.draw(isSelecting, selected);
		}
		drawTask(0.37f, ResidentDrawTask::elemDraw);

		Draw.flush();

		drawSeq.exec();
		drawTask(20, ResidentDrawTask::endDraw);
		Draw.flush();
		Draw.z(21);

		if (!debugBounds.enabled() || drawPadElem == null) return;
		Vec2 vec2;
		if (drawPadElem.parent != null) {
			vec2 = ElementUtils.getAbsolutePos(drawPadElem.parent);
		} else if (drawPadElem == scene.root) {
			vec2 = Tmp.v1.set(0, 0);
		} else { return; }

		Draw.color();
		Draw.alpha(0.7f);
		ScreenSampler.pause();
		drawPad(drawPadElem, vec2);
		ScreenSampler._continue();
	}
	private void drawTask(float z, Consumer<ResidentDrawTask> drawTaskCons) {
		Draw.draw(z, runT(() -> drawResidentTasks.forEach(drawTaskCons)));
	}
	/** 如果选中的元素太小，会在边缘显示 */
	private void drawSlightlyIfSmall() {
		if (selected == null || selected.getWidth() > width / 3f || selected.getHeight() > height / 3f) { return; }

		Vec2    mouse = input.mouse();
		boolean right = mouse.x < width / 2f;
		Tmp.v1.x = right ? width - selected.getWidth() : 0;
		boolean top = mouse.y < height / 2f;
		Tmp.v1.y = top ? height - selected.getHeight() : 0;

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
		Draw.color();
	}


	private static final Vec2 tmp1 = new Vec2();
	public static void drawPad(Element elem, Vec2 vec2) {
		if (!drawHiddenPad.enabled() && !elem.visible) return;
		/* translation也得参与计算 */
		elem.localToStageCoordinates(vec2.set(0, 0));

		if (checkCullingArea.enabled()) {
			elem.localToParentCoordinates(tmp1.set(0, 0));
			if (elem.cullable && elem.parent != null && elem.parent.getCullingArea() != null &&
			    !elem.parent.getCullingArea().overlaps(tmp1.x, tmp1.y,
			     elem.getWidth(), elem.getHeight())) { return; }
		}

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
		if (elem instanceof Table table) {
			ReviewElement.drawMargin(vec2, table);
			ReviewElement.drawPadding(elem, vec2, table);
		}
		if (elem == drawPadElem && elem.parent instanceof Table table) {
			ReviewElement.drawPadding(elem, vec2, table);
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

	Element previousKeyboardFocus = null;
	/** used to dispose */
	private static final Seq<EventListener> listeners = new Seq<>();
	public boolean addCaptureListener(EventListener listener) {
		listeners.add(listener);
		return scene.addCaptureListener(listener);
	}
	public TopGroup() {
		addSceneListener();

		if (IntVars.isDesktop()) {
			addCaptureListener(new HitterListener());
		}

		fillParent = true;
		touchable = Touchable.childrenOnly;
		name = NPX."TopGroup";

		scene.add(this);
		final Group[] all = {back, windows, frag, infos, new FillEnd()};
		for (Group group : all) {
			group.fillParent = true;
			group.touchable = Touchable.childrenOnly;
			super.addChild(group);
		}
		// update(this::toFront);

		TASKS.add(() -> {
			toFront();
			if (checkUICount.enabled()) {
				int max = maxUICount.getInt();
				if (scene.root.getChildren().count(el -> el.visible) > max) {
					tester.loop = false;
					Dialog dialog;
					while (true) {
						dialog = scene.getDialog();
						if (dialog == null) break;
						dialog.hide();
					}
				}
				if (windows.getChildren().count(el -> el.visible) > max) {
					windows.getChildren().<Window>as().each(Window::hide);
				}

			}
		});

		Core.app.post(() -> ScreenSampler.init());
	}

	public Seq<Window> acquireShownWindows() {
		shownWindows.clear();
		Cons<Element> cons = elem -> {
			if (elem instanceof Window window && !(elem instanceof DelayDisposable)) {
				shownWindows.add(window);
			}
		};
		windows.forEach(cons);
		infos.forEach(cons);
		return shownWindows;
	}

	public static final boolean enableGroup = true;

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
		if (enableGroup) {
			(actor instanceof BackInterface ? back
			 : actor instanceof IInfo ? infos
			 : actor instanceof Window ? windows
			 : actor instanceof Frag ? frag
			 : infos).addChild(actor);
			return;
		}
		scene.add(actor);
	}

	public Element hit(float x, float y, boolean touchable) {
		/*isSwicthWindows ? end.hit(x, y, touchable) : */
		Element el = super.hit(x, y, touchable);
		if (el == null || el == this || el.touchable == Touchable.disabled) return null;
		return el;
	}
	public String toString() {
		return "TopGroup[mod-tools]";
	}
	/* 	public void requestSelectRegion
			(TouchDown touchDown,
			 TouchDragged touchDragged,
			 TouchUp touchUp) {

	} */

	public void requestSelectElem(Drawer drawer, Cons<Element> callback) {
		requestSelectElem(drawer, Element.class, callback);
	}

	/**
	 * 请求选择元素
	 * @param drawer      用于选择时渲染
	 * @param elementType 选择元素的类型
	 * @param callback    选择元素后的回调
	 */
	public <T extends Element> void requestSelectElem(Drawer drawer, Class<T> elementType,
	                                                  Cons<T> callback) {
		if (callback == null) throw new IllegalArgumentException("'callback' is null");
		if (isSelecting) throw new IllegalStateException("Cannot call it twice.");
		selected = null;
		isSelecting = true;
		previousKeyboardFocus = scene.getKeyboardFocus();
		scene.setKeyboardFocus(topGroup);
		elementDrawer = drawer;
		this.elementType = elementType;
		elementCallback = as(callback);
	}

	/* 过滤掉的选择元素 */
	public ObjectFloatMap<Element> filterSelected = new ObjectFloatMap<>();
	private Element getSelected0(float x, float y) {
		Element actor = scene.root.hit(x, y, !selectInvisible.enabled());
		while (actor != null && !elementType.isInstance(actor)) actor = actor.parent;
		return actor;
	}
	// 获取指定位置的元素
	public void getSelected(float x, float y) {
		selected = getSelected0(x, y);
	}

	public static final ObjectSet<Element>  searchBlackList = new ObjectSet<>();
	public static final ObjectSet<Class<?>> classBlackList  = new ObjectSet<>();

	public Drawer        elementDrawer   = null;
	public Class<?>      elementType     = Element.class;
	public Cons<Element> elementCallback = null;

	public static final Color  maskColor     = Color.valueOf("#00000033");
	public static final Drawer defaultDrawer = (selecting, el) -> {
		if (!selecting) return;
		Draw.color();
		TopGroup.drawFocus(el, ElementUtils.getAbsolutePos(el), IntUI.DEF_FOCUS_COLOR);
	};

	/** 用于获取元素 */
	private void addSceneListener() {
		scene.root.getCaptureListeners().insert(0, new InputListener() {
			private boolean locked      = false;
			private boolean cancelEvent = false;

			private final Element mask = new FillElement() {
				@Override
				public Element hit(float x, float y, boolean touchable) {
					return cancelEvent ? this : null;
				}
			};

			private void unfocus() {
				scene.setKeyboardFocus(previousKeyboardFocus);
				previousKeyboardFocus = null;
			}

			private void cancel() {
				isSelecting = false;
				unfocus();
				resetSelectElem();
			}

			@Override
			public boolean keyDown(InputEvent event, KeyCode keycode) {
				if (!isSelecting) return true;

				if (keycode == KeyCode.escape) {
					cancel();
				} else if (keycode == KeyCode.f && selected != null) {
					filterElem(selected);
				} else if (keycode == KeyCode.p && selected != null) {
					selected = selected.parent;
				}

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

			@Override
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (locked) {
					if (Vars.mobile) {
						filterElem(getSelected0(x, y));
					}
					return false;
				}

				if (event.listenerActor.isDescendantOf(searchBlackList::contains)
				    || !isSelecting) {
					return false;
				}

				locked = true;
				topGroup.addChild(mask);
				cancelEvent = true;
				event.cancel();

				cancelEvent = false;
				getSelected(x, y);
				cancelEvent = true;
				return true;
			}

			@Override
			public void touchDragged(InputEvent event, float x, float y, int pointer) {
				cancelEvent = false;
				getSelected(x, y);
				cancelEvent = true;
			}

			private boolean filter() {
				if (selected == null) return false;

				Element parent = selected;
				while (parent != null) {
					Class<?> cl = parent.getClass();
					for (Class<?> bcl : classBlackList) {
						if (bcl.isAssignableFrom(cl)) return false;
					}
					parent = parent.parent;
				}
				return true;
			}

			@Override
			public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
				locked = false;
				mask.remove();

				filterSelected.each(entry -> {
					entry.key.translation.x = entry.value;
					entry.key.visible = true;
				});
				filterSelected.clear();

				if (elementCallback != null && filter()) {
					elementCallback.get(selected);
				}

				cancel(); // reset
			}
		});
	}

	public void resetSelectElem() {
		selected = null;
		elementDrawer = null;
		elementType = Element.class;
		elementCallback = null;
	}


	public final List<ResidentDrawTask> drawResidentTasks = new ArrayList<>();

	{
		Events.run(Trigger.uiDrawBegin, delegate(
		 () -> drawResidentTasks.forEach(ResidentDrawTask::init), drawResidentTasks::isEmpty)
		);
		Events.run(Trigger.uiDrawEnd, delegate(
		 () -> drawResidentTasks.forEach(ResidentDrawTask::afterAll), drawResidentTasks::isEmpty)
		);
	}

	public void focusOnElement(FocusTask task) {
		drawResidentTasks.add(task);
	}
	public void removeFocusElement(FocusTask task) {
		drawResidentTasks.remove(task);
	}


	/**
	 * <p>just a flag</p>
	 * 这会被添加到TopGroup的最底层
	 * @see modtools.ui.TopGroup#addChild
	 */
	public static class BackElement extends Element implements BackInterface { }

	public interface BackInterface { }

	//----------

	public interface Drawer {
		default void drawMask() {
			Draw.color(maskColor);
			Fill.crect(0, 0, graphics.getWidth(), graphics.getHeight());
		}
		void draw(boolean selecting, Element selected);
	}

	HKeyCode closeWindow = HKeyCode.data.dynamicKeyCode("closeWindow", () -> new HKeyCode(KeyCode.f4).shift())
	 .applyToScene(true, () -> {
		 if (!shownWindows.isEmpty()) frontWindow.hide();
	 });
	/** press `esc` to hide hitter. */
	static class HitterListener extends InputListener {
		public boolean keyDown(InputEvent event, KeyCode keycode) {
			hitter:
			if (!Core.scene.hasField() && keycode == KeyCode.escape && Hitter.any()) {
				Hitter peek = Hitter.peek();
				if (!peek.canHide()) break hitter;
				peek.hide();
				if (!Hitter.contains(peek)) HopeInput.justPressed.remove(KeyCode.escape.ordinal());
			}
			return super.keyDown(event, keycode);
		}
	}

	@Override
	public void dispose() {
		clear();
		listeners.each(l -> scene.removeCaptureListener(l));
		drawResidentTasks.clear();
		remove();
	}
	@Override
	public boolean isDisposed() {
		return getScene() == null;
	}

	public class GroupHitter extends Hitter {
		{
			touchablility = () -> switchView.isSwitchWindows ? Touchable.enabled : Touchable.disabled;
			clicked(switchView::resetSwitch);
		}
	}

	public Seq<Boolp> disabledSwitchPreviewSeq = new Seq<>();


	/* 切换窗口的UI渲染 */
	class SwitchView extends Table {
		// 切换窗口的实现
		public boolean isSwitchWindows = false;
		public int     currentIndex    = 0;
		boolean built = false;

		Pixmap  pixmap;
		Texture texture;

		{
			TopGroup.this.addCaptureListener(Vars.mobile ? new SwitchGestureListener() : new SwitchInputListener());

			margin(10, 12, 10, 12);
			name = "switchView";
			touchablility = () -> isSwitchWindows ? Touchable.childrenOnly : Touchable.disabled;
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
			Tools.runLoggedException(super::draw);
		}


		public void act(float delta) {
			super.act(delta);
			if (shownWindows.isEmpty()) {
				resetSwitch();
			}
		}
		public void updateVisibility() {
			visible = isSwitchWindows;
		}

		void removeWindow(Element el) {
			el.actions(Actions.fadeOut(0.2f, Interp.fade), Actions.remove());
			currentIndex = 0;
		}
		void build() {
			switchView.clearChildren();
			boolean disabled = false;
			for (Boolp boolp : disabledSwitchPreviewSeq) {
				disabled |= boolp.get();
			}
			if (disabled) return;

			built = true;
			Table paneTable = new Table();
			switchView.pane(paneTable).grow().with(
			 s -> s.setScrollingDisabled(OS.isWindows, false));
			int       W          = graphics.getWidth(), H = graphics.getHeight();
			float     eachW      = W > H ? W / 3f : W / 4f - 16f, eachH = H / 3f;
			Element[] tappedElem = {null}, hoveredElem = {null};
			Table     table      = paneTable.row().table().get();
			for (Window w : shownWindows) {
				Image el = new Image(w.screenshot());

				// TextureRegionDrawable drawable = new TextureRegionDrawable((TextureRegionDrawable) Tex.pane);
				boolean[] cancelEvent = {false};
				float     bestScl     = eachW / eachH, realScl = el.getWidth() / el.getHeight();
				float     width1      = bestScl < realScl ? eachW : eachH * realScl;
				if (table.getPrefWidth() + Scl.scl(width1) > W - 30) table.row();
				table.button(t -> {
					t.margin(4, 6, 4, 6);
					t.act(0);
					t.tapped(() -> tappedElem[0] = t);
					IntUI.hoverAndExit(t,
					 () -> hoveredElem[0] = t,
					 () -> hoveredElem[0] = null);
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
				}, HopeStyles.flatToggleMenut, IntVars.EMPTY_RUN).pad(6, 8, 6, 8).update(t -> {
					if (!w.isShown()) {
						removeWindow(t);
					}
					t.setChecked(tappedElem[0] == null ? t.getZIndex() == currentIndex : tappedElem[0] == t);
				});
			}
		}
		void resolveSwitch() {
			resetSwitch();
			if (currentIndex < shownWindows.size) shownWindows.get(currentIndex).toFront();
		}
		void resetSwitch() {
			isSwitchWindows = false;
			built = false;
		}


		/**
		 * 移动端用于切换窗口的事件侦听器
		 * @see SwitchInputListener
		 */
		class SwitchGestureListener extends ElementGestureListener {
			int lastTouches;
			public void touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				lastTouches = input.getTouches();
			}
			public void fling(InputEvent event, float velocityX, float velocityY, KeyCode button) {
				// Log.info("fling: (@, @, @) ", velocityX, velocityY, lastTouches);
				if (velocityX < 1000 || lastTouches != 3) return;
				if (!isSwitchWindows) {
					currentIndex = frontWindow != null ? shownWindows.indexOf(frontWindow) : 0;
				}
				isSwitchWindows = true;
				if (!built) {
					build();
				}
			}
		}

		public static HKeyCode switchWindowKey = HKeyCode.data.dynamicKeyCode("switchWindow", () -> new HKeyCode(KeyCode.tab).ctrl());

		/**
		 * 电脑端用于切换窗口的事件侦听器
		 * @see SwitchGestureListener
		 */
		class SwitchInputListener extends InputListener {
			public boolean keyDown(InputEvent event, KeyCode keycode) {
				// acquireShownWindows();
				if (shownWindows.isEmpty()) return false;
				if ((switchWindowKey.isPress()) /* || (Vars.mobile && keycode == KeyCode.volumeDown) */) {
					if (!built) {
						build();
					}
					if (!isSwitchWindows) {
						currentIndex = frontWindow != null ? shownWindows.indexOf(frontWindow) : 0;
					}
					if (shownWindows.size > 1) currentIndex -= Mathf.sign(Core.input.shift());

					currentIndex = ArrayUtils.rollIndex(currentIndex, shownWindows.size);
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

		public boolean fire(SceneEvent event) {
			boolean[] b = {false};
			Tools.runShowedException(() -> b[0] = super.fire(event));
			return b[0];
		}
	}

	public static class BoolfDrawTasks extends TaskSet { }

	public interface ResidentDrawTask {
		default void backDraw() { }

		default void elemDraw() { }
		/**
		 * 在{@code drawer}渲染之前渲染
		 * @param drawer 等一会会渲染的元素
		 */
		default void beforeDraw(Window drawer) { }
		default void endDraw() { }
		default void init() { }
		default void afterAll() { }
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
		public void drawFocus(Element elem, Vec2 pos) {
			TopGroup.drawFocus(elem, pos, focusColor);
		}
		public void endDraw() {
			if (maskColor.a == 0) return;

			Draw.color(maskColor);
			Fill.crect(0, 0, Core.graphics.getWidth(), Core.graphics.getHeight());

			if (drawSlightly) topGroup.drawSlightlyIfSmall();
		}
	}

	private static final Mat     oldTransform     = new Mat();
	private static final Vec2    transVec         = new Vec2();
	public static void drawFocus(Element elem, Vec2 pos, Color focusColor) {
		drawFocus(elem, pos, focusColor, null);
	}
	public static void drawFocus(Element elem, Vec2 pos, Color focusColor, Cons<Vec2> otherDraw) {
		Gl.flush();

		Group transformParent = elem instanceof Group ? (Group) elem : elem.parent;
		while (transformParent != null) {
			if (transformParent.isTransform()) break;
			transformParent = transformParent.parent;
		}
		if (transformParent != null && (transformParent instanceof ScrollPane || transformParent.parent instanceof ScrollPane)) {
			transformParent = null;
		}

		if (transformParent != null) {
			oldTransform.set(Draw.trans());
			Mat computedTransform = Reflect.invoke(Group.class, transformParent, "computeTransform", ArrayUtils.EMPTY_ARRAY);
			Draw.trans(computedTransform);
			pos = elem == transformParent ? transVec.set(0, 0) : elem.localToAscendantCoordinates(transformParent, transVec.set(0, 0));
		}

		if (focusColor.a > 0) {
			float alpha = focusColor.a * (elem.visible ? 0.9f : 0.6f);
			if (alpha != 0 && ElementUtils.checkInStage(pos)) {
				// 填充背景
				Draw.color(focusColor, alpha);
				// Tmp.m1.set(Draw.trans());
				Fill.crect(pos.x, pos.y, elem.getWidth(), elem.getHeight());
			}
		}
		if (!elem.visible) {
			Draw.color(Pal.accent);
			TextureRegionDrawable icon = Icon.eyeOffSmall;
			icon.draw(Mathf.clamp(pos.x, 0, graphics.getWidth() - icon.getMinWidth()),
			 Mathf.clamp(pos.y, 0, graphics.getHeight() - icon.getMinHeight()),
			 14, 14);
		}
		// 虚线框
		Draw.color(Pal.accent);
		float thick = 1f;
		Lines.stroke(thick);
		Drawf.dashRectBasic(pos.x - thick, pos.y - thick, elem.getWidth() + thick, elem.getHeight() + thick);

		if (otherDraw != null) otherDraw.get(pos);

		if (transformParent != null) {
			Draw.trans(oldTransform);
		}
	}

	private class FillEnd extends Table {
		public FillEnd() {
			super(t -> {
				t.addChild(new GroupHitter());
				t.add(TopGroup.this.switchView);
			});
		}
		public Element hit(float x, float y, boolean touchable) {
			return or(super.hit(x, y, touchable), switchView.isSwitchWindows ? this : null);
		}
	}

}
