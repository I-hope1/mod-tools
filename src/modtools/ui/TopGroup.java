package modtools.ui;

import arc.Core;
import arc.Events;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.ui.Dialog;
import arc.struct.Seq;
import mindustry.game.EventType;
import modtools.ui.components.Window;

import static modtools.IntVars.modName;
import static modtools.IntVars.topGroup;
import static modtools.ui.Contents.tester;

// 存储mod的窗口和Frag
public final class TopGroup extends Group {
	public boolean checkUI = true;

	{
		fillParent = true;
		name = modName + "-topTable";
		// Core.scene.add(this);

		Events.run(EventType.Trigger.update, () -> {
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

	@Override
	public void addChild(Element actor) {
		Core.scene.add(actor);
	}
}
