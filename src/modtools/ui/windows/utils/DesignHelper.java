package modtools.ui.windows.utils;

import arc.Core;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.Seq;
import mindustry.gen.*;
import modtools.jsfunc.INFO_DIALOG;
import modtools.ui.components.windows.ListDialog.ModifiedLabel;
import modtools.ui.content.ui.ShowUIList;
import modtools.ui.content.ui.design.DesignTable;
import modtools.ui.content.ui.design.DesignTable.Status;

public class DesignHelper {
	public static void design() {
		DesignTable<?> table = new DesignTable<>(new WidgetGroup());

		Table label = modifiedLabel("Nothing");
		INFO_DIALOG.dialog(d -> {
			d.add(table).grow().minSize(376, 256).row();
			d.table(Tex.pane, buttons -> {
				buttons.button("Text", Icon.addSmall, () -> {
					table.addChild(modifiedLabel("Some Text"));
				}).growX();
				buttons.button("Image", Icon.addSmall, () -> {
					Image actor = new Image(Tex.nomap);
					actor.setSize(42);
					actor.clicked(() -> actor.setDrawable(Seq.with(ShowUIList.iconKeyMap.keySet()).random()));
					table.addChild(actor);
				}).growX();
			}).growX().row();
			d.table(Tex.pane, buttons -> {
				ButtonGroup<CheckBox> group = new ButtonGroup<>() {
					public boolean canCheck(CheckBox button, boolean newState) {
						boolean canCheck = super.canCheck(button, newState);
						if (canCheck) table.changeStatus(Status.valueOf(button.name));
						return canCheck;
					}
				};
				group.setMinCheckCount(1);
				group.setMaxCheckCount(1);

				group.add(
				 checkBox("Move", "move"),
				 checkBox("Edit", "edit"),
				 checkBox("Delete", "delete")
				);
				group.setChecked("Move");
				for (CheckBox button : group.getButtons()) {
					buttons.add(button).growX();
				}
			}).growX();
		});
		Core.app.post(() -> table.addChild(label));
	}
	private static CheckBox checkBox(String text, String name) {
		CheckBox box = new CheckBox(text);
		box.name = name;
		return box;
	}
	private static Table modifiedLabel(String defaultText) {
		String[] text = {defaultText};
		Table    t    = new Table();
		ModifiedLabel.build(() -> text[0], _ -> true, (f, l) -> {
			text[0] = f.getText();
		}, t);
		return t;
	}
}
