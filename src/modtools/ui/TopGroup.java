package modtools.ui;

import arc.Core;
import arc.Events;
import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.ui.Dialog;
import arc.struct.*;
import mindustry.game.EventType;
import mindustry.game.EventType.Trigger;
import modtools.ui.components.Window;
import modtools.utils.*;

import java.util.Iterator;

import static modtools.IntVars.modName;
import static modtools.IntVars.topGroup;
import static modtools.ui.Contents.*;

// 存储mod的窗口和Frag
public final class TopGroup extends Group {
	public boolean checkUI = MySettings.settings.getBool("checkUI", "false"),
			debugBounds = MySettings.settings.getBool("debugbounds", "false");
	public MyObjectSet<Boolp> drawSeq = new MyObjectSet<>();

	public void draw() {
		super.draw();
		drawSeq.filter(Boolp::get);
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
		} else Draw.color(Color.white);
		Lines.rect(offsetX, offsetY,
				elem.getWidth(), elem.getHeight());
	}

	{
		fillParent = true;
		name = modName + "-topTable";

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

		update(this::toFront);

		Events.run(Trigger.update, () -> {
			// toFront();
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

	public boolean ok = false;

	@Override
	public void addChild(Element actor) {
		if (ok) {
			super.addChild(actor);
			return;
		}
		Core.scene.add(actor);
	}
}
