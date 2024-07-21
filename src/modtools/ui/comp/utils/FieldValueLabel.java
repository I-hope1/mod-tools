package modtools.ui.comp.utils;

import arc.scene.ui.*;
import arc.scene.ui.Button.ButtonStyle;
import arc.scene.ui.Dialog.DialogStyle;
import arc.scene.ui.ImageButton.ImageButtonStyle;
import arc.scene.ui.ScrollPane.ScrollPaneStyle;
import arc.scene.ui.TextField.TextFieldStyle;
import arc.struct.Seq;
import arc.util.*;
import mindustry.gen.Icon;
import modtools.events.E_JSFunc;
import modtools.ui.IntUI;
import modtools.ui.comp.input.JSRequest;
import modtools.ui.menu.MenuItem;
import modtools.utils.reflect.FieldUtils;

import java.lang.reflect.*;

public class FieldValueLabel extends ReflectValueLabel {
	private final Field field;

	public FieldValueLabel(Object newVal, Class<?> type, Field field, Object obj) {
		super(type, obj, field.getModifiers());
		if (newVal != null && newVal != unset && !type.isPrimitive() && !type.isInstance(newVal))
			throw new IllegalArgumentException("Type(" + type + ") mismatches value(" + newVal + ").");
		// markupEnabled = true;
		this.field = field;

		if (newVal != unset) setVal(newVal);

		if (field != null) {
			Runnable r = () -> {
				if (E_JSFunc.auto_refresh.enabled() && enableUpdate) {
					flushVal();
				}
			};
			update(r);
			/* update(() -> {
				if (!E_JSFunc.update_async.enabled()) r.run();
			}); */
		}
	}

	public void setNewVal(Object newVal) {
		if (obj instanceof Button b && newVal instanceof ButtonStyle s) {
			if (s instanceof ImageButtonStyle is) {
				is = new ImageButtonStyle(is);
				ImageButtonStyle previous = (ImageButtonStyle) b.getStyle();
				is.imageUp = previous.imageUp;
				is.imageDown = previous.imageDown;
				is.imageOver = previous.imageOver;
				is.imageUpColor = previous.imageUpColor;
				is.imageDownColor = previous.imageDownColor;
				is.imageOverColor = previous.imageOverColor;
				newVal = s = is;
			}
			b.setStyle(s);
		} else if (obj instanceof ScrollPane p && newVal instanceof ScrollPaneStyle s) {
			p.setStyle(s);
		} else if (obj instanceof TextField f && newVal instanceof TextFieldStyle s) {
			f.setStyle(s);
		} else if (obj instanceof ProgressBar p && newVal instanceof ProgressBar.ProgressBarStyle s) {
			p.setStyle(s);
		} else if (obj instanceof Dialog d && newVal instanceof DialogStyle s) {
			d.setStyle(s);
		}

		setFieldValue(newVal);
	}
	public void flushVal() {
		if (isValid()) {
			Object value = FieldUtils.getFieldValue(isStatic ? field.getDeclaringClass() : obj, getOffset(), field.getType());
			setVal(value);
		} else {
			setText0(null);
		}
	}
	Long offset;
	public long getOffset() {
		if (field == null) throw new RuntimeException("Field is null.");
		if (offset == null) offset = FieldUtils.fieldOffset(field);
		return offset;
	}

	public float getMinWidth() {
		return 96;
	}
	public Seq<MenuItem> getMenuLists() {
		Seq<MenuItem> list = new Seq<>();
		basicMenuLists(list);
		if (field != null && !type.isPrimitive()) {
			list.add(MenuItem.with("selection.set", Icon.editSmall, "@selection.reset", () -> {
				JSRequest.requestForField(val, obj, o -> setFieldValue(type.cast(o)));
			}));
		}
		return list;
	}

	public void setFieldValue(Object val) {
		// Tools.setFieldValue(field, obj, val);
		if (field.getType().isPrimitive()) {
			try {
				field.set(obj, val);
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			}
		} else {
			FieldUtils.setValue(
			 isStatic ? field.getDeclaringClass() : obj,
			 getOffset(), val, field.getType());
		}

		setVal(val);
	}

	protected Seq<MenuItem> basicMenuLists(Seq<MenuItem> list) {
		return super.basicMenuLists(list).removeAll(l -> "@clear".equals(l.getName()));
	}
	public void addEnumSetter() {
		clicked(() -> IntUI.showSelectListEnumTable(this,
		 Seq.with(type.getEnumConstants()).<Enum>as(),
		 () -> (Enum) val, this::setFieldValue,
		 Float.NEGATIVE_INFINITY, 42,
		 true, Align.left));
	}
	public boolean isValid() {
		return field != null && (obj != null || isStatic);
	}
	public boolean readOnly() {
		return !isStatic && getObject() == null;
	}
}
