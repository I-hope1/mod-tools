package modtools.ui.components.utils;

import arc.struct.Seq;
import arc.util.*;
import mindustry.gen.Icon;
import modtools.events.*;
import modtools.ui.IntUI;
import modtools.ui.IntUI.MenuList;
import modtools.ui.components.input.JSRequest;
import modtools.utils.reflect.FieldUtils;

import java.lang.reflect.*;

public class FieldValueLabel extends ValueLabel {
	public @Nullable        Object obj;
	private final @Nullable Field  field;

	public Object getObject() {
		return obj;
	}

	public FieldValueLabel(Object newVal, Class<?> type, Field field, Object obj) {
		super(type);
		if (newVal != null && newVal != unset && !type.isPrimitive() && !type.isInstance(newVal))
			throw new IllegalArgumentException("type(" + type + ") mismatches value(" + newVal + ").");
		// markupEnabled = true;
		if (field != null) isStatic = Modifier.isStatic(field.getModifiers());
		this.obj = obj;
		this.field = field;

		if (newVal != unset) setVal(newVal);

		if (field != null) {
			update(() -> {
				if (E_JSFunc.auto_refresh.enabled() && enableUpdate) {
					setVal();
				}
			});
		}
	}

	public void setVal() {
		if (field == null || (obj == null && !isStatic)) {
			setVal0(null);
		} else {
			Object value = FieldUtils.getFieldValue(isStatic ? field.getDeclaringClass() : obj, getOffset(), field.getType());
			setVal0(value);
		}
	}
	Long offset;
	public long getOffset() {
		if (field == null) throw new RuntimeException("field is null");
		if (offset == null) offset = FieldUtils.fieldOffset(field);
		return offset;
	}

	public Seq<MenuList> getMenuLists() {
		Seq<MenuList> list = new Seq<>();
		basicMenuLists(list);
		if (field != null && !type.isPrimitive()) list.add(MenuList.with(Icon.editSmall, "@selection.reset", () -> {
			JSRequest.requestForField(val, obj, o -> {
				setFieldValue(type.cast(o));
			});
		}));
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

	public void addEnumSetter() {
		clicked(() -> IntUI.showSelectListEnumTable(this,
		 Seq.with(type.getEnumConstants()).<Enum>as(),
		 () -> (Enum) val, this::setFieldValue,
		 Float.NEGATIVE_INFINITY, 42,
		 true, Align.left));
	}
}
