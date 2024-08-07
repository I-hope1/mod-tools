package modtools.ui.comp.review;

import arc.Core;
import arc.func.Func;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.event.Touchable;
import arc.scene.style.Style;
import arc.scene.ui.*;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import modtools.ui.*;
import modtools.ui.comp.Window;
import modtools.ui.comp.Window.IDisposable;
import modtools.ui.comp.input.MyLabel;
import modtools.ui.comp.utils.*;
import modtools.content.ui.ReviewElement;
import modtools.content.ui.ReviewElement.CellView;
import modtools.utils.*;
import modtools.utils.reflect.FieldUtils;
import modtools.utils.ui.*;

import java.lang.reflect.Field;

import static modtools.ui.HopeStyles.defaultLabel;
import static modtools.utils.Tools.*;
import static modtools.utils.ui.FormatHelper.fixedAny;

public class CellDetailsWindow extends Window implements IDisposable, CellView {
	public static final Color themeColor = Pal.accent;

	final Cell<?> cl;
	public CellDetailsWindow(Cell<?> cell) {
		super("cell");
		this.cl = cell;

		cont.table(Tex.pane, t -> {
			t.defaults().grow();
			t.add();
			buildSetter(t, cell, "padTop");
			t.add().row();
			buildSetter(t, cell, "padLeft");

			ValueLabel label = new PlainValueLabel<>(Element.class, cell::get);
			label.enableUpdate = false;
			label.update(() -> label.setVal(cell.get()));
			Label   placeholder     = new MyLabel("<VALUE>", defaultLabel);
			Cell<?> placeholderCell = t.add(placeholder).pad(6f);
			placeholder.clicked(() -> placeholderCell.setElement(label));
			label.clicked(() -> placeholderCell.setElement(placeholder));

			buildSetter(t, cell, "padRight").row();
			t.add();
			buildSetter(t, cell, "padBottom");
			t.add();
		}).colspan(2).growX().row();
		cont.left().defaults().height(32).growX().left();
		cont.add("Align: ").color(themeColor);
		cont.label(() -> FormatHelper.align(CellTools.align(cell))).row();
		cont.defaults().colspan(2);
		buildWithName(cont, cell, "minWidth");
		buildWithName(cont, cell, "minHeight");
		buildWithName(cont, cell, "maxWidth");
		buildWithName(cont, cell, "maxHeight");
		buildWithName(cont, cell, "colspan", Float::intValue);
		cont.defaults().colspan(1);
		checkboxField(cont, cell, "fillX");
		checkboxField(cont, cell, "fillY");
		cont.row();
		checkboxField(cont, cell, "expandX");
		checkboxField(cont, cell, "expandY");
		cont.row();
		checkboxField(cont, cell, "uniformX");
		checkboxField(cont, cell, "uniformY");
		cont.row();
		TextButtonStyle style = HopeStyles.flatBordert;
		fnButton("Layout", style, runT(() -> cell.getTable().layout()));
		fnButton("Invalidate", style, runT(() -> cell.getTable().invalidateHierarchy()));
		cont.row();
		fnButton("GrowX", style, cell::growX);
		fnButton("GrowY", style, cell::growY);
		cont.row();
		fnButton("Left", style, cell::left);
		fnButton("Right", style, cell::right);
		cont.row();
		fnButton("Top", style, cell::top);
		fnButton("Bottom", style, cell::bottom);
		cont.row();
		fnButton("Center", style, cell::center);
		checkboxField(cont, cell, "endRow");
		cont.row();
		// cont.table(Tex.pane, t -> {
		// 	t.touchable = Touchable.enabled;
		// 	t.add("Element");
		// 	ReviewElement.addFocusSource(t, () -> this, cell::get);
		// }).growX().colspan(2);

		ReviewElement.addFocusSource(this, () -> this, cell::get);

	}
	private void fnButton(String text, TextButtonStyle style, Runnable listener) {
		cont.button(text, style, () -> {
			listener.run();
			if (cl.getTable() != null) cl.getTable().layout();
		});
	}
	static Cell<Table> buildSetter(Table t, Cell<?> cell, String name) {
		return t.add(ReviewElement.floatSetter(null, () -> FormatHelper.fixed(Reflect.get(Cell.class, cell, name)), f -> {
			Reflect.set(Cell.class, cell, name, f);
			if (cell.get() != null) cell.get().invalidateHierarchy();
		}));
	}
	static Cell<Table> buildWithName(Table t, Cell<?> cell, String name) {
		return buildWithName(t, cell, name, f -> f);
	}
	public static <T> Boolean getChecked(Class<? extends T> ctype, T obj, String key) {
		return SR.of(Reflect.get(ctype, obj, key))
		 .reset(CellDetailsWindow::asBoolean)
		 .get();
	}

	private static boolean asBoolean(Object t) {
		return t instanceof Boolean ? (Boolean) t :
		 t instanceof Number n && n.intValue() == 1;
	}
	static <T extends Number> Cell<Table> buildWithName(Table t, Cell cell, String name,
	                                                    Func<Float, T> valueOf) {
		Table table = ReviewElement.floatSetter(name + ": ",
		 () -> fixedAny(Reflect.get(Cell.class, cell, name)),
		 f -> {
			 Reflect.set(Cell.class, cell, name, valueOf.get(f));
			 Core.app.post(() -> {
				 if (cell.get() != null) cell.get().invalidateHierarchy();
			 });
		 });
		table.left();
		return CellTools.rowSelf(t.add(table));
	}

	public static Cell<CheckBox> checkboxField(Table cont, Cell<?> obj, String key) {
		return checkboxField(cont, Cell.class, obj, key);
	}

	public static <T>
	Cell<CheckBox> checkboxField(Table cont, Class<? extends T> ctype, T obj, String key) {
		Field    field     = FieldUtils.getFieldAccessOrThrow(ctype, key);
		Class<?> valueType = field.getType();
		return cont.check(Strings.capitalize(key), 28, getChecked(ctype, obj, key), b -> {
			 Tools.runShowedException(() -> field.set(obj, castBoolean(valueType, b)));

			 // flush
			 if (obj instanceof Table t) {
				 t.layout();
			 } else if (obj instanceof Cell<?> c) {
				 c.getTable().layout();
			 }
		 })
		 /** {@link Cell#style(Style)}会报错 */
		 .with(t -> t.setStyle(HopeStyles.hope_defaultCheck))
		 .with(chk -> {
			 if (valueType == float.class || valueType == int.class) {
				 addFloatSetter(obj, field,chk, valueType == int.class);
			 }
		 }).checked(_ -> getChecked(ctype, obj, key))
		 .fill(false)
		 .expand(false, false).left();
	}
	private static <T> void addFloatSetter(
	 T obj, Field jfield, CheckBox elem, boolean useInt) {
		IntUI.addTooltipListener(elem, IntUI.tips("exact_setter"));
		EventHelper.longPressOrRclick(elem, _ -> {
			IntUI.showSelectTable(elem, (p, _, _) -> {
				Number defvalue = Reflect.get(obj, jfield);
				Slider slider   = new Slider(useInt ? -2 : -1, 2, useInt ? 1 : 0.01f, false);
				slider.setValue(defvalue.floatValue());
				TextField field = new TextField(FormatHelper.fixed(defvalue.floatValue()));
				field.setValidator(useInt ? Strings::canParseInt : NumberHelper::isFloat);
				slider.moved(v -> {
					Number val;

					// 装箱时最好别用三目表达式
					if (useInt) val = (int) v;
					else val = v;

					Tools.runShowedException(() -> jfield.set(obj, val));
					field.setText(FormatHelper.fixed(v));
				});
				field.changed(() -> {
					Number value = useInt ? NumberHelper.asInt(field.getText()) : NumberHelper.asFloat(field.getText());
					Tools.runShowedException(() -> jfield.set(obj, value));
					slider.setValue(value.floatValue());
				});
				Label label = new Label(() -> FormatHelper.fixedAny(Reflect.get(obj, jfield)));
				label.setAlignment(Align.center);
				label.touchable = Touchable.disabled;
				p.stack(slider, label).growX().row();
				p.add(field);
			}, false, Align.top);
		});
	}

	static Object castBoolean(Class<?> valueType, boolean b) {
		return valueType == Boolean.TYPE ? b : Mathf.num(b);
	}

	// Mat mat = new Mat();
	public void drawFocus(Element focus) {
		drawFocus(cl, focus);
	}
}