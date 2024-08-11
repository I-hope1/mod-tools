package modtools.ui.windows.utils;

import arc.scene.ui.CheckBox;
import mindustry.ui.dialogs.BaseDialog;
import modtools.content.ui.design.DesignView;

public class DesignHelper {
	public static void design() {
		/* DesignTable<?> table = new DesignTable<>(new WidgetGroup());

		DesignLabel label = new DesignLabel("Nothing");
		INFO_DIALOG.dialog(d -> {
			d.add(table).grow().minSize(376, 256).row();
			d.table(Tex.pane, buttons -> {
				buttons.button("Text", Icon.addSmall, () -> {
					table.addChild(new DesignLabel("Some Text"));
				}).growX();
				buttons.button("Image", Icon.addSmall, () -> {
					table.addChild(new DesignImage());
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
				buttons.button(Icon.menu, HopeStyles.flati, IntVars.EMPTY_RUN)
				 .with(b -> b.clicked(() -> MenuBuilder.showMenuListFor(b, Align.top, () -> Seq.with(
					MenuItem.with("save", Icon.saveSmall, "Save", table::save),
					MenuItem.with("load", Icon.download, "Load", table::load),
					MenuItem.with("export.code", Icon.export, "Export As Java", table::export)
				 ))));
			}).growX();
		}); */
		new BaseDialog("") {{
			add(new DesignView()).grow();
		}}.show();
	}
	private static CheckBox checkBox(String text, String name) {
		CheckBox box = new CheckBox(text);
		box.name = name;
		return box;
	}
}
