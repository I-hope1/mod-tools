package modtools.ui;

import arc.Core;
import arc.Events;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.event.*;
import arc.scene.ui.Dialog;
import arc.struct.*;
import arc.util.*;
import mindustry.game.EventType.Trigger;
import mindustry.graphics.Pal;
import modtools.ui.components.Window;
import modtools.utils.*;

import java.util.ArrayList;

import static modtools.IntVars.*;
import static modtools.ui.Contents.*;

// 存储mod的窗口和Frag
public final class TopGroup extends Group {
	public boolean checkUI = MySettings.settings.getBool("checkUI", "false"),
			debugBounds = MySettings.settings.getBool("debugbounds", "false");
	public MySet<Boolp> drawSeq = new MySet<>();
	public boolean isSwicthWindows = false;
	public int currentIndex = 0;
	public ArrayList<Window> shownWindows = new ArrayList<>();

	public void draw() {
		super.draw();
		drawSeq.filter(Boolp::get);

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
					Core.scene.setKeyboardFocus(null);
					if (!isSwicthWindows) {
						currentIndex = Window.focusWindow != null ? shownWindows.indexOf(Window.focusWindow) : 0;
					}
					currentIndex += Core.input.shift() ? -1 : 1;
					if (currentIndex < 0) {
						currentIndex += shownWindows.size();
					} else if (currentIndex >= shownWindows.size()) {
						currentIndex -= shownWindows.size();
					}
					Log.info(currentIndex);
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
		touchable = Touchable.enabled;
		name = modName + "-topTable";
		// Log.info("Loaded top group");

		// Core.scene.add(this);
		/* 显示UI布局 */
		drawSeq.add(() -> {
			if (!debugBounds) return true;
			Draw.color(Color.white);
			Draw.alpha(0.7f);
			Lines.stroke(1);
			drawPad(Core.scene.root, 0, 0);
			Draw.flush();
			return true;
		});
		Core.scene.add(this);
		// update(this::toFront);

		Events.run(Trigger.update, () -> {
			shownWindows.clear();
			children.each(elem -> {
				if (elem instanceof Window) {
					shownWindows.add((Window) elem);
				}
			});
			toFront();
			if (checkUI) {
				if (Core.scene.root.getChildren().select(el -> el.visible).size > 70) {
					tester.loop = false;
					Dialog dialog;
					while (true) {
						dialog = Core.scene.getDialog();
						if (dialog == null) break;
						dialog.hide();
					}
				}
				if (topGroup.getChildren().select(el -> el.visible).size > 70) {
					Seq<Element> windows = topGroup.getChildren().select(el -> el instanceof Window);
					windows.each(Element::remove);
				}

			}
		});
	}

	public boolean ok = true;

	public void addChild(Element actor) {
		if (ok) {
			super.addChild(actor);
			return;
		}
		Core.scene.add(actor);
	}
}
