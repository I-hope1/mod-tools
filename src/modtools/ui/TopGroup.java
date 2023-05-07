package modtools.ui;

import arc.*;
import arc.func.Cons;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.FrameBuffer;
import arc.input.KeyCode;
import arc.math.Interp;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.actions.Actions;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.ObjectSet;
import arc.util.*;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.graphics.MyShaders;
import modtools.ui.components.Window;
import modtools.ui.effect.*;
import modtools.utils.*;

import java.util.ArrayList;

import static arc.Core.*;
import static modtools.IntVars.modName;
import static modtools.ui.Contents.tester;
import static modtools.ui.IntUI.topGroup;
import static modtools.ui.effect.ScreenSampler.bufferCaptureAll;
import static modtools.utils.MySettings.SETTINGS;
import static modtools.utils.Tools.or;

// 存储mod的窗口和Frag
public final class TopGroup extends WidgetGroup {
	public boolean checkUI = SETTINGS.getBool("checkUI", false),
			debugBounds    = SETTINGS.getBool("debugbounds", false);
	/* 渲染相关 */
	public TaskSet drawSeq     = new TaskSet();
	public TaskSet backDrawSeq = new TaskSet();

	public boolean           isSwicthWindows = false;
	public int               currentIndex    = 0;
	public ArrayList<Window> shownWindows    = new ArrayList<>();

	public Group getTopG() {
		return others;
	}
	// public Group getTopW() {
	// 	return windows;
	// }
	private final Group
			back    = new Group() {
		public void draw() {
			backDrawSeq.exec();
			super.draw();
		}
		{name = "back";}
	},
			windows = new Group() {{name = "windows";}},
			frag    = new Group() {{name = "frag";}},
			others  = new WidgetGroup() {{name = "others";}};
	private final Table end = new MyEnd();

	private final Group[] all         = {back, windows, frag, others, new Table(t -> {
		t.add(end);
	}) {
		public Element hit(float x, float y, boolean touchable) {
			return or(super.hit(x, y, touchable), isSwicthWindows ? this : null);
		}
	}};
	public        Element drawPadElem = null;

	public void draw() {
		super.draw();

		drawSelected:
		if (elementDrawer != null && selected != null) {
			// Draw.flush();
			elementDrawer.draw(selecting, selected);
			if (selected.getWidth() > width / 3f || selected.getHeight() > height / 3f) break drawSelected;

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
			// Log.info("x: @, y: @", x, y);
		}

		if (true) return;
		/* 切换窗口 */
		if (isSwicthWindows) {
			drawSwitchText();
		}
	}
	private void drawSwitchText() {
		float tw = graphics.getWidth(), th = graphics.getHeight();
		Draw.color(Color.darkGray);
		Fill.polyBegin();
		Font  font = MyFonts.MSYHMONO;
		float rw   = 0, rh = shownWindows.size() * font.getLineHeight();
		var   l    = new GlyphLayout(font, "");
		for (Window window : shownWindows) {
			l.setText(font, window.title.getText());
			rw = Math.max(rw, l.width);
		}
		rw += 16f;
		l.free();
		float offsetY = (th + rh) / 2f;
		Fill.rect(tw / 2, th / 2, rw, rh + 16f);
		int i = 0;
		for (Window window : shownWindows) {
			font.setColor(i++ == currentIndex ? Pal.accent : Color.white);
			font.draw(window.title.getText(), tw / 2, offsetY, Align.center);
			offsetY -= font.getLineHeight();
		}
	}

	public static boolean drawHiddenPad = false;
	public static void drawPad(Element elem, Vec2 vec2) {
		if (!drawHiddenPad && !elem.visible) return;
		vec2.x += elem.x;
		vec2.y += elem.y;
		if (elem instanceof Group) {
			Draw.color(Color.sky);
		} else {
			Draw.color(Color.white);
		}

		Lines.rect(vec2.x, vec2.y,
				elem.getWidth(), elem.getHeight());

		if (elem instanceof Group) {
			Vec2 cpy = vec2.cpy();
			for (var e : ((Group) elem).getChildren()) {
				drawPad(e, vec2.set(cpy));
			}
		}
	}

	{
		addSceneLinstener();
		Core.scene.addListener(new SwitchInputListener());

		fillParent = true;
		touchable = Touchable.childrenOnly;
		name = modName + "-TopGroup";
		// Log.info("Loaded top group");

		// Core.scene.add(this);
		/* 显示UI布局 */
		Events.run(Trigger.uiDrawEnd, () -> {
			drawSeq.exec();
			Draw.flush();

			if (!debugBounds && drawPadElem == null) return;
			Element drawPadElem = or(this.drawPadElem, Core.scene.root);
			Vec2    vec2;
			if (drawPadElem.parent != null) {
				vec2 = Tools.getAbsPos(drawPadElem.parent);
			} else if (drawPadElem == Core.scene.root) {
				vec2 = Tmp.v1.set(0, 0);
			} else return;
			Draw.color(Color.white);
			Draw.alpha(0.7f);
			Lines.stroke(1);
			drawPad(drawPadElem, vec2);

			Draw.flush();
		});
		Core.scene.add(this);
		for (Group group : all) {
			group.fillParent = true;
			group.touchable = Touchable.childrenOnly;
			super.addChild(group);
		}
		// update(this::toFront);

		Events.run(Trigger.update, () -> {
			shownWindows.clear();
			windows.forEach(elem -> {
				if (elem instanceof Window) {
					shownWindows.add((Window) elem);
				}
			});
			toFront();
			if (checkUI) {
				if (Core.scene.root.getChildren().count(el -> el.visible) > 70) {
					tester.loop = false;
					Dialog dialog;
					while (true) {
						dialog = Core.scene.getDialog();
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
			if (shownWindows.isEmpty()) {
				resetSwitch();
			}
			end.visible = isSwicthWindows;
			end.touchable = isSwicthWindows ? Touchable.childrenOnly : Touchable.disabled;
		});
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
					: actor instanceof Window ? windows
					: actor instanceof Frag ? frag
					: others).addChild(actor);
			return;
		}
		Core.scene.add(actor);
	}

	public Element hit(float x, float y, boolean touchable) {
		return /*isSwicthWindows ? end.hit(x, y, touchable) : */super.hit(x, y, touchable);
	}


	private Element selected, tmp;
	public boolean isSelecting() {
		return selecting;
	}
	private boolean selecting;
	private boolean cancelEvent;

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
			Core.scene.unfocusAll();
			elementDrawer = drawer;
			elementCallback = callback;
		} else {
			resetSelectElem();
		}
	}

	// 获取指定位置的元素
	public void getSelected(float x, float y) {
		tmp = Core.scene.root.hit(x, y, true);
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
	private void addSceneLinstener() {
		Core.scene.addCaptureListener(new InputListener() {
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
			public boolean keyDown(InputEvent event, KeyCode keycode) {
				if (keycode == KeyCode.escape) {
					cancel();
				}
				return super.keyDown(event, keycode);
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
				// Time.runTask(20, () -> {
				// cancel(actor);
				mask.remove();
				// });
				selecting = false;
				if (elementCallback != null && filter()) elementCallback.get(selected);
				resetSelectElem();
			}
		});
	}
	private void resetSelectElem() {
		elementDrawer = null;
		elementCallback = null;
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

	boolean once = false;

	private class SwitchInputListener extends InputListener {
		public boolean keyDown(InputEvent event, KeyCode keycode) {
			if (shownWindows.isEmpty()) return false;
			if ((keycode == KeyCode.tab && Core.input.ctrl()) || (Vars.mobile && keycode == KeyCode.volumeDown)) {
				if (!once) {
					resolveOnce();
					once = true;
				}
				Core.scene.setKeyboardFocus(TopGroup.this);
				if (!isSwicthWindows) {
					currentIndex = Window.focusWindow != null ? shownWindows.indexOf(Window.focusWindow) : 0;
				}
				if (shownWindows.size() > 1) currentIndex += Core.input.shift() ? -1 : 1;
				if (currentIndex < 0) {
					currentIndex += shownWindows.size();
				} else if (currentIndex >= shownWindows.size()) {
					currentIndex -= shownWindows.size();
				}
				// Log.info(currentIndex);
				// currentIndex = Mathf.clamp(currentIndex, 0, shownWindows.size() - 1);
				isSwicthWindows = true;
			}
			return true;
			/*var children = Window.focusWindow.parent.getChildren();
			children.get(Window.focusWindow.getZIndex() + 1 % children.size).toFront();*/
		}

		public boolean keyUp(InputEvent event, KeyCode keycode) {
			if (event.cancelled) return false;
			if (shownWindows.isEmpty()) return false;
			if (isSwicthWindows && !Core.input.ctrl()) {
				resolveSwitch();
			}
			return true;
		}
	}
	private void resolveSwitch() {
		resetSwitch();
		if (currentIndex < shownWindows.size()) shownWindows.get(currentIndex).toFront();
	}
	private void resetSwitch() {
		isSwicthWindows = false;
		once = false;
	}
	private void resolveOnce() {
		end.clearChildren();
		Table end = (Table) this.end.pane(new Table()).grow()
				.with(s -> s.setScrollingDisabled(true, false))
				.get().getWidget();
		int             W          = graphics.getWidth(), H = graphics.getHeight();
		float           eachW      = W / 4f - 16f, eachH = H / 3f;
		final Element[] tappedElem = {null}, hoveredElem = {null};
		for (int i = 0; i < shownWindows.size(); i++) {
			if (i % 4 == 0) end.row();
			final int finalI = i;
			Window    w      = shownWindows.get(i);
			Element   el     = new Image(w.screenshot());

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
					t1.button(Icon.cancel, Styles.clearNonei, () -> {
						w.hide();
						cancelEvent[0] = true;
					}).visible(() -> hoveredElem[0] == t);
				}).growX().pad(6, 8, 6, 8).row();
				t.add(el).size(bestScl < realScl ? eachW : eachH * realScl,
						bestScl < realScl ? eachW / realScl : eachH);
			}, Styles.flatToggleMenut, () -> {
				if (cancelEvent[0]) {
					removeWinodw(hoveredElem[0]);
					return;
				}
				currentIndex = finalI;
				resolveSwitch();
			}).pad(6, 8, 6, 8).update(t -> {
				if (!w.isShown()) {
					removeWinodw(t);
				}
				t.setChecked(tappedElem[0] == null ? finalI == currentIndex : tappedElem[0] == t);
			});
		}
	}
	private void removeWinodw(Element el) {
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
			MyDraw.blur(() -> {
				Fill.crect(x, y, width, height);
			});
			drawBack();
			super.draw();
		}
	}

	public interface TouchDown {
		void fire(Vec2 pos, KeyCode button);
	}

	public interface TouchDragged {
		void fire(Vec2 pos);
	}

	public interface TouchUp {
		void fire(Vec2 pos, KeyCode button);
	}
}
