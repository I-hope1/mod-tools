package modtools.ui.components.review;

import arc.Core;
import arc.func.Func;
import arc.scene.Element;
import arc.scene.ui.*;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.gen.Tex;
import modtools.ui.HopeStyles;
import modtools.ui.components.Window;
import modtools.ui.components.Window.IDisposable;
import modtools.ui.components.input.MyLabel;
import modtools.ui.components.utils.*;
import modtools.ui.content.ui.ReviewElement;
import modtools.utils.ui.FormatHelper;

import static modtools.ui.HopeStyles.defaultLabel;
import static modtools.utils.Tools.*;
import static modtools.utils.ui.FormatHelper.fixedAny;

public class CellDetailsWindow extends Window implements IDisposable {
	final Cell<?> cl;
	public CellDetailsWindow(Cell<?> cell) {
		super("cell");
		this.cl = cell;

		cont.table(Tex.pane, t -> {
			t.defaults().grow();
			t.add();
			getAndAdd(t, cell, "padTop");
			t.add().row();
			getAndAdd(t, cell, "padLeft");
			ValueLabel label = new PlainValueLabel<>(Element.class, cell::get);
			label.enableUpdate = false;
			label.update(() -> label.setVal(cell.get()));
			Label   placeholder     = new MyLabel("<VALUE>", defaultLabel);
			Cell<?> placeholderCell = t.add(placeholder).pad(6f);
			placeholder.clicked(() -> placeholderCell.setElement(label));
			label.clicked(() -> placeholderCell.setElement(placeholder));

			getAndAdd(t, cell, "padRight").row();
			t.add();
			getAndAdd(t, cell, "padBottom");
			t.add();
		}).colspan(2).growX().row();
		cont.left().defaults().height(32).growX().left();
		cont.defaults().colspan(2);
		getAddWithName(cont, cell, "minWidth").row();
		getAddWithName(cont, cell, "minHeight").row();
		getAddWithName(cont, cell, "maxWidth").row();
		getAddWithName(cont, cell, "maxHeight").row();
		getAddWithName(cont, cell, "colspan", Float::intValue).row();
		cont.defaults().colspan(1);
		checkboxField(cont, cell, "fillX", float.class);
		checkboxField(cont, cell, "fillY", float.class);
		cont.row();
		checkboxField(cont, cell, "expandX", int.class);
		checkboxField(cont, cell, "expandY", int.class);
		cont.row();
		checkboxField(cont, cell, "uniformX", boolean.class);
		checkboxField(cont, cell, "uniformY", boolean.class);
		cont.row();
		TextButtonStyle style = HopeStyles.flatBordert;
		cont.button("Layout", style, catchRun(() -> cell.getTable().layout()));
		cont.button("Invalidate", style, catchRun(() -> cell.getTable().invalidateHierarchy()));
		cont.row();
		cont.button("GrowX", style, cell::growX);
		cont.button("GrowY", style, cell::growY);
		cont.row();
		cont.button("Left", style, cell::left);
		cont.button("Right", style, cell::right);
		cont.row();
		cont.button("Top", style, cell::top);
		cont.button("Bottom", style, cell::bottom);
		cont.row();
		checkboxField(cont, cell, "endRow", boolean.class).colspan(2);

		ReviewElement.addFocusSource(this, () -> this, cell::get);
	}
	static Cell<Table> getAndAdd(Table t, Cell cell, String name) {
		return t.add(ReviewElement.floatSetter(null, () -> FormatHelper.fixed(Reflect.get(Cell.class, cell, name)), f -> {
			Reflect.set(Cell.class, cell, name, f);
			if (cell.get() != null) cell.get().invalidateHierarchy();
		}));
	}
	static Cell<Table> getAddWithName(Table t, Cell cell, String name) {
		return getAddWithName(t, cell, name, f -> f);
	}
	public static <T> Boolean getChecked(Class<? extends T> ctype, T obj, String key) {
		return Sr(Reflect.get(ctype, obj, key))
		 .reset(t -> t instanceof Boolean ? (Boolean) t :
			t instanceof Number n && n.intValue() == 1)
		 .get();
	}
	static <T extends Number> Cell<Table> getAddWithName(Table t, Cell cell, String name,
																											 Func<Float, T> valueOf) {
		Table table = ReviewElement.floatSetter(name + ": ", () -> fixedAny(Reflect.get(Cell.class, cell, name)), f -> {
			Reflect.set(Cell.class, cell, name, valueOf.get(f));
			Core.app.post(() -> {
				if (cell.get() != null) cell.get().invalidateHierarchy();
			});
		});
		table.left();
		return t.add(table);
	}
	/* private static <T> void field(Table cont, Cell<?> cell, String key, TextFieldValidator validator,
																	Func<String, T> func) {
			cont.table(t -> {
				t.add(key);
				ModifiedLabel.build(() -> String.valueOf(Reflect.get(Cell.class, cell, key)),
				 validator, (field, label) -> {
					 if (!field.isValid()) return;
					 Reflect.set(Cell.class, cell, key, func.get(field.getText()));
					 label.setText(field.getText());
				 }, 2, t);
			});
		} */
	public static Cell<CheckBox> checkboxField(Table cont, Cell<?> obj, String key,
																						 Class<?> valueType) {
		return checkboxField(cont, Cell.class, obj, key, valueType);
	}

	public static <T>
	Cell<CheckBox> checkboxField(Table cont, Class<? extends T> ctype, T obj, String key,
															 Class<?> valueType) {
		return cont.check(Strings.capitalize(key), 28, getChecked(ctype, obj, key), b -> {
			 Reflect.set(ctype, obj, key, valueType == Boolean.TYPE ? b : b ? 1 : 0);
			 if (obj instanceof Table t) t.layout();
			 else if (obj instanceof Cell<?> c) c.getTable().layout();
		 })
		 .with(t -> t.setStyle(HopeStyles.hope_defaultCheck))
		 .checked(_ -> getChecked(ctype, obj, key)).fill(false).expand(false, false).left();
	}
}
