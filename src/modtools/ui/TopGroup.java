package modtools.ui;

import arc.*;
import arc.func.Cons;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.Interp;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.actions.Actions;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Icon;
import mindustry.graphics.*;
import mindustry.ui.Styles;
import modtools.graphics.MyShaders;
import modtools.ui.IntUI.PopupWindow;
import modtools.ui.components.Window;
import modtools.ui.effect.*;
import modtools.utils.*;

import java.util.ArrayList;

import static arc.Core.*;
import static modtools.IntVars.modName;
import static modtools.ui.Contents.tester;
import static modtools.ui.IntUI.topGroup;
import static modtools.ui.components.Window.frontWindow;
import static modtools.ui.effect.ScreenSampler.bufferCaptureAll;
import static modtools.utils.MySettings.SETTINGS;
import static modtools.utils.Tools.*;

// 存储mod的窗口和Frag
public final class TopGroup extends WidgetGroup {
	public boolean checkUI = SETTINGS.getBool("checkUI"),
	  debugBounds          = SETTINGS.getBool("debugbounds"),
	  selectUnvisible = SETTINGS.getBool("selectUnvisible");
	/* 渲染相关 */
	public BoolfDrawTasks drawSeq     = new BoolfDrawTasks();
	public BoolfDrawTasks backDrawSeq = new BoolfDrawTasks();

	public boolean isSwitchWindows = false;
	public int     currentIndex    = 0;
	public ArrayList<Window> shownWindows    = new ArrayList<>();

	public Group getTopG() {
		return others;
	}
	// public Group getTopW() {
	// 	return windows;
	// }
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
	  back    = new Group() {
		public void draw() {
			backDrawSeq.exec();
			drawResidentTasks.each(ResidentDrawTask::backDraw);
			super.draw();
		}
		{name = "back";}
	},
	  windows = new Group() {{name = "windows";}},
	  frag    = new Group() {{name = "frag";}},
	  others  = new WidgetGroup() {
		  public Element hit(float x, float y, boolean touchable) {
			  return sr(super.hit(x, y, touchable))
				.set(children.contains(t -> t instanceof Window && t instanceof PopupWindow)
				  ? el -> or(el, this) : null)
				.get();
		  }
		  {
			  name = "others";
		  }
	  };
	private final Table end = new MyEnd();

	public Element drawPadElem = null;

	public void draw() {
		super.draw();

		if (elementDrawer != null && selected != null) {
			// Draw.flush();
			elementDrawer.draw(selecting, selected);
		}
		drawResidentTasks.each(ResidentDrawTask::elemDraw);
	}
	/** 如果选中的元素太小，会在边缘显示 */
	private void drawSlightlyIfSmall() {
		if (selected == null || selected.getWidth() > width / 3f || selected.getHeight() > height / 3f) return;

		Vec2 mouse = input.mouse();
		Tmp.v1.x = mouse.x < width / 2f ? width - selected.getWidth() : 0;
		Tmp.v1.y = mouse.y < height / 2f ? height - selected.getHeight() : 0;
		// Tools.screenshot(selected, true, null).texture;
		// buffer.blit(MyShaders.Specl);
		// scene.getCamera().bounds(Tmp.r1.set(selected.x, selected.y, selected.getWidth(), selected.getHeight()));
		Draw.blit(bufferCaptureAll(Tmp.v1, selected), MyShaders.baseShader);
		Draw.color(ColorFul.color);
		Lines.stroke(4f);
		Lines.line(mouse.x, mouse.y, Tmp.v1.x, Tmp.v1.y);
		Draw.flush();
	}

	public static boolean drawHiddenPad = false;
	public static void drawPad(Element elem, Vec2 vec2) {
		if (!drawHiddenPad && !elem.visible) return;
		/* translation也得参与计算 */
		vec2.x += elem.x + elem.translation.x;
		vec2.y += elem.y + elem.translation.y;
		Draw.color(elem instanceof Group ? Color.sky : Color.green);

		Lines.rect(vec2.x, vec2.y,
		  elem.getWidth(), elem.getHeight());

		if (elem instanceof Group group) {
			Vec2 cpy = vec2.cpy();
			for (var e : group.getChildren()) {
				drawPad(e, vec2.set(cpy));
			}
		}
	}

	public TopGroup() {
		if (topGroup != null) throw new IllegalStateException("topGroup已被加载");
		addSceneListener();
		scene.addListener(new SwitchInputListener());
		scene.addListener(new CloseWindowListener());

		fillParent = true;
		touchable = Touchable.childrenOnly;
		name = modName + "-TopGroup";
		// Log.info("Loaded top group");

		// Core.scene.add(this);
		/* 显示UI布局 */
		Events.run(Trigger.uiDrawEnd, () -> {
			drawSeq.exec();

			drawResidentTasks.each(ResidentDrawTask::endDraw);
			Draw.flush();

			if (!debugBounds && drawPadElem == null) return;
			Element drawPadElem = or(this.drawPadElem, scene.root);
			Vec2    vec2;
			if (drawPadElem.parent != null) {
				vec2 = Tools.getAbsPos(drawPadElem.parent);
			} else if (drawPadElem == scene.root) {
				vec2 = Tmp.v1.set(0, 0);
			} else return;
			Draw.color(Color.white);
			Draw.alpha(0.7f);
			Lines.stroke(1);
			drawPad(drawPadElem, vec2);

			Draw.flush();
		});
		scene.add(this);
		Group[] all = {back, windows, frag, others, new Table(t -> t.add(end)) {
			public Element hit(float x, float y, boolean touchable) {
				return or(super.hit(x, y, touchable), isSwitchWindows ? this : null);
			}
		}};
		for (Group group : all) {
			group.fillParent = true;
			group.touchable = Touchable.childrenOnly;
			super.addChild(group);
		}
		// update(this::toFront);

		tasks.add(() -> {
			toFront();
			if (checkUI) {
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
	public ArrayList<Window> acquireShownWindows() {
		shownWindows.clear();
		Cons<Element> cons = elem -> {
			if (elem instanceof Window window) {
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
		return sr(super.hit(x, y, touchable))
		  .setnull(el -> el == this || el == null || el.touchable == Touchable.disabled)
		  .get();
	}

/* 	public void requestSelectRegion
			(TouchDown touchDown,
			 TouchDragged touchDragged,
			 TouchUp touchUp) {

	} */

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
	// 获取指定位置的元素
	public void getSelected(float x, float y) {
		Element tmp = scene.root.hit(x, y, !selectUnvisible);
		selected = tmp;
		//if (tmp != null) {
			/*do {
				selected = tmp;
				tmp.parentToLocalCoordinates(Tmp.v1.set(x, y));
				tmp = selected.hit(x = Tmp.v1.x, y = Tmp.v1.y, true);
			} while (tmp != null && selected != tmp);*/
		// }
		// Log.info(selected);
	}

	public static final ObjectSet<Element>  searchBlackList = new ObjectSet<>();
	public static final ObjectSet<Class<?>> classBlackList  = new ObjectSet<>();

	public Cons<Element> elementCallback = null;
	public Drawer        elementDrawer   = null;

	/** 用于获取元素 */
	private void addSceneListener() {
		scene.addCaptureListener(new InputListener() {
			final Element mask = new Element() {
				{
					fillParent = true;
				}

				@Override
				public Element hit(float x, float y, boolean touchable) {
					return cancelEvent ? this : null;
				}
			};
			public void cancel() {
				selecting = false;
			}
			/* 拦截keydown */
			public boolean keyDown(InputEvent event, KeyCode keycode) {
				if (!selecting) return false;
				if (keycode == KeyCode.escape) {
					event.cancel();
					cancel();
				}
				if (keycode == KeyCode.f && selected != null) {
					selected.visible = false;
					filterSelected.put(selected, selected.translation.x);
					selected.translation.x = scene.getWidth() * 2;
					// Log.info(selected);
				}
				return false;
			}
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (event.listenerActor.isDescendantOf(searchBlackList::contains)) return false;
				if (!selecting) return false;
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


	private final Seq<ResidentDrawTask> drawResidentTasks = new Seq<>();
	public void focusOnElement(FocusTask task) {
		drawResidentTasks.add(task);
	}
	public void removeFocusElement(FocusTask task) {
		drawResidentTasks.remove(task);
	}


	/**
	 * just a flag
	 *
	 * @see modtools.ui.TopGroup#addChild
	 */
	public static class BackElement extends Element implements BackInterface {}

	public interface BackInterface {}


	public interface Drawer {
		void draw(boolean selecting, Element selected);
	}


	boolean KAL_once = false;

	/** 用于切换窗口的事件侦听器 */
	private class SwitchInputListener extends InputListener {
		public boolean keyDown(InputEvent event, KeyCode keycode) {
			acquireShownWindows();
			if (shownWindows.isEmpty()) return false;
			if ((keycode == KeyCode.tab && Core.input.ctrl()) || (Vars.mobile && keycode == KeyCode.volumeDown)) {
				if (!KAL_once) {
					resolveOnce();
					KAL_once = true;
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
	private void resolveOnce() {
		end.clearChildren();
		Table end = new Table();
		this.end.pane(end).grow().with(
		  s -> s.setScrollingDisabled(true, false));
		int             W          = graphics.getWidth(), H = graphics.getHeight();
		float           eachW      = W > H ? W / 3f : W / 4f - 16f, eachH = H / 3f;
		final Element[] tappedElem = {null}, hoveredElem = {null};
		for (int i = 0; i < shownWindows.size(); i++) {
			if (i % 4 == 0) end.row();
			Window w  = shownWindows.get(i);
			Image  el = new Image(w.screenshot());

			// TextureRegionDrawable drawable = new TextureRegionDrawable((TextureRegionDrawable) Tex.pane);
			boolean[] cancelEvent = {false};
			float     bestScl     = eachW / eachH, realScl = el.getWidth() / el.getHeight();
			end.button(t -> {
				t.margin(4, 6, 4, 6);
				t.act(0.1f);
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
				t.add(el).size(bestScl < realScl ? eachW : eachH * realScl,
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

		default void endDraw() {}
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
		public void backDraw() {
		}
		public void elemDraw() {
			drawFocus(elem);
		}
		public void drawFocus(Element elem) {
			Vec2 vec2 = getAbsPos(elem);
			if (focusColor.a > 0) {
				Draw.color(focusColor);
				Fill.crect(vec2.x, vec2.y, elem.getWidth(), elem.getHeight());
			}

			Draw.color(Pal.accent);
			float thick = 1f;
			Lines.stroke(thick);
			Drawf.dashRectBasic(vec2.x, vec2.y - thick, elem.getWidth() + thick, elem.getHeight() + thick);
		}
		public void endDraw() {
			if (maskColor.a > 0) {
				Draw.color(maskColor);
				Fill.crect(0, 0, Core.graphics.getWidth(), Core.graphics.getHeight());

				if (drawSlightly) topGroup.drawSlightlyIfSmall();
			}
		}
	}
}
