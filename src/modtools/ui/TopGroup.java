package modtools.ui;

import arc.Core;
import arc.Events;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.event.*;
import arc.scene.ui.Dialog;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import modtools.ui.components.Window;
import modtools.utils.*;
import modtools.utils.Tools.SR;

import java.util.ArrayList;

import static modtools.IntVars.*;
import static modtools.ui.Contents.*;

// 存储mod的窗口和Frag
public final class TopGroup extends WidgetGroup {
	public boolean checkUI = MySettings.settings.getBool("checkUI", "false"),
			debugBounds = MySettings.settings.getBool("debugbounds", "false");
	public MySet<Boolp> drawSeq = new MySet<>();
	public boolean isSwicthWindows = false;
	public int currentIndex = 0;
	public ArrayList<Window> shownWindows = new ArrayList<>();
	private final Group
			back = new Group() {{name = "back";}},
			windows = new Group() {{name = "windows";}},
			frag = new Group() {{name = "frag";}},
			others = new WidgetGroup() {{name = "others";}};
	private final Group[] all = {back, windows, frag, others};
	public Element drawPadElem = null;

	public void draw() {
		super.draw();

		if (isSwicthWindows) {
			float tw = Core.graphics.getWidth(), th = Core.graphics.getHeight();
			Draw.color(Color.darkGray);
			Fill.polyBegin();
			Font font = MyFonts.MSYHMONO;
			float rw = 0, rh = shownWindows.size() * font.getLineHeight();
			var l = new GlyphLayout(font, "");
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
	}

	public static void drawPad(Element elem, float offsetX, float offsetY) {
		if (!elem.visible) return;
		offsetX += elem.x;
		offsetY += elem.y;
		if (elem instanceof Group) {
			for (var e : ((Group) elem).getChildren()) {
				drawPad(e, offsetX, offsetY);
			}
			Draw.color(Color.sky);
		} else {
			Draw.color(Color.white);
		}

		Lines.rect(offsetX, offsetY,
				elem.getWidth(), elem.getHeight());
	}

	{
		Core.scene.addListener(new InputListener() {
			public boolean keyDown(InputEvent event, KeyCode keycode) {
				if (shownWindows.isEmpty()) return false;
				if (keycode == KeyCode.tab && Core.input.ctrl()) {
					Core.scene.setKeyboardFocus(TopGroup.this);
					if (!isSwicthWindows) {
						currentIndex = Window.focusWindow != null ? shownWindows.indexOf(Window.focusWindow) : 0;
					}
					currentIndex += Core.input.shift() ? -1 : 1;
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
				if (isSwicthWindows && !Core.input.ctrl()) {
					isSwicthWindows = false;
					shownWindows.get(currentIndex).toFront();
				}
				return true;
			}
		});

		fillParent = true;
		touchable = Touchable.childrenOnly;
		name = modName + "-TopGroup";
		// Log.info("Loaded top group");

		// Core.scene.add(this);
		/* 显示UI布局 */
		Events.run(Trigger.uiDrawEnd, () -> {
			drawSeq.filter(Boolp::get);
			Draw.flush();

			if (!debugBounds && drawPadElem == null) return;
			Element drawPadElem = Tools.or(this.drawPadElem, Core.scene.root);
			if (drawPadElem.parent != null) {
				Tools.getAbsPos(drawPadElem.parent);
			} else if (drawPadElem == Core.scene.root) {
				Tmp.v1.set(0, 0);
			} else return;
			Draw.color(Color.white);
			Draw.alpha(0.7f);
			Lines.stroke(1);
			drawPad(drawPadElem, Tmp.v1.x, Tmp.v1.y);

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
	}

	public static final boolean enabled = true;

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
		return isSwicthWindows ? this : super.hit(x, y, touchable);
	}


	/**
	 * just a flag
	 */
	public static class BackElement extends Element implements BackInterface {}

	public interface BackInterface {}
}
