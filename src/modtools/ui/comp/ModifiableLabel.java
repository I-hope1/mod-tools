package modtools.ui.comp;

import arc.Core;
import arc.func.*;
import arc.scene.ui.*;
import arc.scene.ui.TextField.TextFieldValidator;
import arc.scene.ui.layout.*;
import modtools.ui.comp.input.NoMarkupLabel;
import modtools.ui.comp.input.area.AutoTextField;

/**
 * 可以修改的Label
 */
public class ModifiableLabel extends NoMarkupLabel {
	public static Cell<?> build(
	 Prov<CharSequence> def,
	 TextFieldValidator validator,
	 Cons2<TextField, Label> modifier,
	 Table t) {
		return build(def, validator, modifier, t, AutoTextField::new);
	}

	TextField               field;
	Cell<?>                 cell;
	Cons2<TextField, Label> modifier;
	public ModifiableLabel(Prov<CharSequence> sup) {
		super(sup);
	}

	public static Cell<?> build(
	 Prov<CharSequence> def,
	 TextFieldValidator validator,
	 Cons2<TextField, Label> modifier,
	 Table t, Prov<TextField> fieldProv) {
		var label = new ModifiableLabel(def);
		label.cell = t.add(label).height(label.getPrefHeight() / Scl.scl());
		label.field = fieldProv.get();
		if (validator != null) label.field.setValidator(validator);
		label.modifier = modifier;
		label.init();
		return label.cell;
	}

	public void init() {
		field.update(() -> {
			if (Core.scene.getKeyboardFocus() != field) {
				modifier.get(field, this);
				setText(field.getText());
				cell.setElement(this);
			}
		});
		clicked(() -> {
			Core.scene.setKeyboardFocus(field);
			field.setText(getText() + "");
			field.setCursorPosition(getText().length());
			cell.setElement(field);
		});
	}
}
