package modtools.ui.windows;

import arc.Core;
import arc.files.Fi;
import arc.func.*;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.TextField.TextFieldValidator;
import modtools.ui.IntUI.PopupWindow;
import modtools.ui.comp.Window;
import modtools.utils.search.BindCell;

import static modtools.ui.comp.windows.ListDialog.fileUnfair;

public class NameWindow extends Window implements PopupWindow {
	TextField    field = new TextField();
	Cons<String> okCons;
	BindCell     errorMessage;

	{
		cont.top().defaults().top();
		cont.table(t -> {
			t.add("@name");
			t.add(field).growX();
		}).growX().row();
		errorMessage = BindCell.ofConst(cont.add("").left().color(Color.red));
		cont.update(() -> errorMessage.toggle(((Label) errorMessage.el).getText().length() > 0));

		buttons.button("@ok", () -> {
			okCons.get(field.getText());
			hide();
		}).growX().disabled(_ -> !field.isValid());
		// closeOnBack();
	}

	public NameWindow() {
		super("", 220, 80, false, false);
	}

	public void show(
	 Cons<String> okCons, TextFieldValidator valid,
	 Prov<CharSequence> errorProv,
	 String text) {
		this.okCons = okCons;
		if (valid != null) field.setValidator(valid);
		field.update(() -> ((Label) errorMessage.el).setText(errorProv.get()));
		field.setText(text);

		field.setMessageText("@message.input");
		field.addListener(new InputListener() {
			@Override
			public boolean keyUp(InputEvent event, KeyCode keycode) {
				if (keycode == KeyCode.enter) {
					okCons.get(field.getText());
					hide();
				}
				return false;
			}
		});
		moveToMouse().show();
	}

	public static class FileNameWindow extends NameWindow {
		private boolean fileNameFair = false;
		public FileNameWindow() { }
		public void show(
		 Cons<String> okCons,
		 String text, Fi parent) {
			super.show(okCons, t -> {
				 try {
					 return fileNameFair = !t.isBlank() && !fileUnfair.matcher(t).find();
				 } catch (Throwable e) { return false; }
			 },

			 () -> !fileNameFair ? "@message.file_name_unfair" :
				parent.child(field.getText()).exists() ? Core.bundle.format("message.file_exists", field.getText()) : "",

			 text);
		}
	}
}