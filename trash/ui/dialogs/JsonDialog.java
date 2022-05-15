package modmake.ui.dialogs;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Color;
import arc.scene.ui.Dialog;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import arc.scene.event.VisibilityListener;

public class JsonDialog extends Dialog {
	Label label = new Label("");
	Table p = new Table();
	Fi file;
	ModsEditor.Meta mod;

	String getText() {
		return file.readString().replaceAll("\r", "\n")
				.replaceAll("\\t", "  ")
				.replaceAll("\\[\\s*([^]*?)\\s*\\]", "[ $1 ]");
	}

	void load() {
		p.center();
		p.defaults().padTop(10).left();
		p.add("$editor.sourceCode", Color.gray).padRight(10).padTop(0).row();
		p.table(t -> {
			t.right();
			t.button(Icon.download, Styles.clearPartiali, () -> Vars.ui
					.showConfirm(
							"粘贴", "是否要粘贴", () -> {
								this.file.writeString(Core.app.getClipboardText());
								label.setText(this.getText());
							})
			);
			t.button(Icon.copy, Styles.clearPartiali, () -> Core.app.setClipboardText(this.file.readString()));
		}).growX().right().row();
		p.pane(p -> p.left().add(label)).width(bw).height(h / 3f);
		cont.pane(p).size(bw, h / 2f).grow().row();

		buttons.button("$back", Icon.left, Styles.defaultt, this::hide).size(bw / 2f, bh);

		var listener = new VisibilityListener() {
			public boolean hidden() {
				// file = Editor.file
				title.setText(file.nameWithoutExtension());
				label.setText(getText());
				// Editor.ui.removeListener(listener);
				return false;
			}
		};
		buttons.button("$edit", Icon.edit, Styles.defaultt, () -> {
			// Editor.edit(this.file, this.mod)
			// Editor.ui.addListener(listener)
		}).size(bw / 2f, bh);
		closeOnBack();
	}

	float w = Core.graphics.getWidth(), h = Core.graphics.getHeight(),
			bw = w > h ? 550 : 450, bh = w > h ? 64 : 70;

	public JsonDialog show(Fi file, ModsEditor.Meta mod) {
		if (file.extension().replaceAll("^h?json$", "") == "") return null;
		this.file = file;
		this.mod = mod;

		title.setText(file.name() != null ? file.name() : "");

		label.setText(this.getText());

		show();
		return this;
	}

	public JsonDialog() {
		super();
	}
}
