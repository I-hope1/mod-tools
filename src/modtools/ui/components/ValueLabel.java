package modtools.ui.components;

import arc.graphics.Color;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.*;
import hope_android.FieldUtils;
import modtools.ui.*;
import modtools.ui.components.input.MyLabel;
import modtools.ui.components.input.highlight.Syntax;
import modtools.utils.*;

import java.lang.reflect.*;

import static ihope_lib.MyReflect.unsafe;
import static modtools.utils.MySettings.D_JSFUNC;
import static modtools.utils.Tools.getAbsPos;

public class ValueLabel extends MyLabel {
	public static final Seq<Class<?>> NUMBER_SEQ = Seq.with(int.class, byte.class, short.class,
			long.class, float.class, double.class);
	public              Object        val;
	private @Nullable   Object        obj;
	private @Nullable   Field         field;
	public final        Class<?>      type;

	public ValueLabel(Object val, Class<?> type) {
		super(String.valueOf(val));
		setStyle(IntStyles.MOMO_Label);
		this.type = type;
		setVal(val);
		setAlignment(Align.left, Align.left);

		update(() -> {
			if (D_JSFUNC.getBool("auto_refresh", false) && field != null) {
				setVal();
			}
		});
	}

	private boolean hasChange = false;
	private float   lastWidth = 0;
	private Long    offset;
	private boolean isStatic;
	public boolean isStatic() {
		return isStatic;
	}
	public long getOffset() {
		if (field == null) throw new RuntimeException("field is null");
		if (offset == null) offset = OS.isAndroid ? FieldUtils.getFieldOffset(field) :
				isStatic ? unsafe.staticFieldOffset(field) : unsafe.objectFieldOffset(field);
		return offset;
	}
	@Override
	public float getPrefWidth() {
		if (hasChange) {
			hasChange = false;
			wrap = false;
			float def = super.getPrefWidth();
			wrap = true;
			lastWidth = Mathf.clamp(def, 40, 800);
		}
		return lastWidth;
	}

	public void setVal(Object val) {
		if (this.val == val) return;
		this.val = val;
		hasChange = true;
		String text = type == String.class && val != null ? '"' + (String) val + '"' : String.valueOf(val);
		setColor(val == null ? Syntax.objectsC
				: type == String.class ? Syntax.stringC
				: NUMBER_SEQ.contains(Tools.unbox(type)) ? JSFunc.NUMBER_COLOR : Color.white);
		if (text.length() > 1000) {
			text = text.substring(0, 1000) + "  ...";
		}
		setText(text);
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
			Tools.setFieldValue(
					isStatic ? field.getDeclaringClass() : obj,
					getOffset(), val, field.getType());
		}

		setVal(val);
	}

	public void clearVal() {
		val = "";
		lastWidth = 0;
		setText("");
	}

	public void setVal() {
		if (field == null || (obj == null && !isStatic)) {
			setVal(null);
		} else {
			setVal(Tools.getFieldValue(isStatic ? field.getDeclaringClass() : obj, getOffset(), field.getType()));
		}
	}

	public static final boolean disabled = true;
	public              boolean addedL;

	public void addShowInfoListener() {
		// disabled
		if (disabled || addedL) return;
		addedL = true;
		IntUI.longPress(this, 600, b -> {
			if (!b) return;
			// 使用Time.runTask避免stack overflow
			Time.runTask(0, () -> {
				var pos = getAbsPos(this);
				try {
					if (val != null) {
						JSFunc.showInfo(val).setPosition(pos);
					} else {
						JSFunc.showInfo(null, type).setPosition(pos);
					}
				} catch (Throwable e) {
					IntUI.showException(e).setPosition(pos);
				}
			});
		});
	}
	public void set(Field field, Object obj) {
		this.field = field;
		isStatic = Modifier.isStatic(field.getModifiers());
		this.obj = obj;
	}
}
