package modtools.ui.components;

import arc.func.Func;
import arc.graphics.*;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.struct.*;
import arc.util.*;
import hope_android.FieldUtils;
import mindustry.Vars;
import mindustry.entities.Effect;
import mindustry.gen.Icon;
import modtools.events.*;
import modtools.ui.*;
import modtools.ui.IntUI.MenuList;
import modtools.ui.components.input.*;
import modtools.ui.components.input.highlight.Syntax;
import modtools.utils.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.Map.Entry;

import static ihope_lib.MyReflect.unsafe;
import static modtools.events.E_JSFunc.truncate_text;
import static modtools.ui.IntUI.copyAsJSMenu;
import static modtools.utils.Tools.*;

public class ValueLabel extends MyLabel {

	public            Object   val;
	public @Nullable  Object   obj;
	private @Nullable Field    field;
	public final      Class<?> type;

	public boolean enableTruncate = true;

	public final Func<Object, String> defFunc = this::dealVal;
	/**
	 * 用于显示label内容
	 * 每次修改时，都会执行这个func，返回值作为显示值
	 */
	public       Func<Object, String> func;


	public ValueLabel(Object newVal, Object obj, Method method) {
		this(newVal, method.getReturnType(), null, obj, method);
		isStatic = Modifier.isStatic(method.getModifiers());
	}
	public ValueLabel(Object newVal, Class<?> type, Field field, Object obj) {
		this(newVal, type, field, obj, null);
	}
	public ValueLabel(Object newVal, Class<?> type, Field field, Object obj, Method method) {
		super(String.valueOf(newVal));
		setStyle(IntStyles.MOMO_LabelStyle);
		this.type = type;
		if (field != null) set(field, obj);
		setVal(newVal);
		setAlignment(Align.left, Align.left);

		update(() -> {
			if (E_JSFunc.auto_refresh.enabled() && field != null) {
				setVal();
			}
		});
		MyEvents.on(E_JSFuncDisplay.value, b -> shown = b.enabled());

		IntUI.addShowMenuListener(this, () -> getMenuLists(type, field, obj));
	}
	Seq<MenuList> list;
	private Seq<MenuList> getMenuLists(Class<?> type, Field field, Object obj) {
		if (list != null) return list;
		list = new Seq<>();
		sr(type).isExtend(cl -> {
			 list.add(MenuList.with(Icon.imageSmall, "img", () -> {
				 if (val instanceof TextureRegion r) JSFunc.dialog(r);
				 else if (val instanceof Texture t) JSFunc.dialog(t);
			 }));
		 }, TextureRegion.class, Texture.class)
		 .isExtend(cl -> {
			 list.add(MenuList.with(Icon.zoomSmall, Contents.reviewElement.name, () -> {
				 JSFunc.reviewElement((Element) val);
			 }));
		 }, Element.class)
		 .isExtend(cl -> {
			 list.add(MenuList.with(Icon.infoSmall, "at player", () -> {
				 ((Effect) val).at(Vars.player);
			 }));
		 }, Effect.class)
		 .isExtend(cl -> {
			 list.add(MenuList.with(Icon.infoSmall, "@details", () -> {
				 showNewInfo(null, cl);
			 }));
		 }, Class.class);
		if (field != null && !type.isPrimitive()) list.add(MenuList.with(Icon.editSmall, "@selection.reset", () -> {
			JSRequest.requestForField(val, obj, o -> {
				setFieldValue(type.cast(o));
			});
		}));

		list.add(new MenuList(Icon.listSmall, () -> (enableTruncate ? "disable" : "enable") + " truncate", () -> {
			enableTruncate = !enableTruncate;
		}));
		list.add(MenuList.with(Icon.eyeSmall, "条件显示", () -> {
			JSRequest.<Func<Object, String>>requestForDisplay(defFunc, obj, o -> func = o);
		}));
		list.add(copyAsJSMenu("value", () -> val));
		return list;
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
		if (this.val == val && (type.isPrimitive() || type == String.class)) return;
		this.val = val;
		hasChange = true;
		if (func == null) resetFunc();
		try {
			setText(func.get(val));
		} catch (Throwable th) {
			IntUI.showException(th);
			resetFunc();
			setText(func.get(val));
		}
	}
	private void resetFunc() {
		func = defFunc;
	}

	public String dealVal(Object val) {
		if (val instanceof ObjectMap) {
			StringBuilder sb = new StringBuilder();
			sb.append('{');
			for (ObjectMap.Entry o : ((ObjectMap<?, ?>) val)) {
				sb.append(dealVal(o.key));
				sb.append('=');
				sb.append(dealVal(o.value));
				if (isTruncate(sb.length())) break;
			}
			sb.deleteCharAt(sb.length() - 1);
			sb.append('}');
			setColor(Syntax.c_map);
			return sb.toString();
		}
		if (val instanceof Iterable) {
			StringBuilder sb = new StringBuilder();
			sb.append('[');
			for (Object o : ((Iterable<?>) val)) {
				sb.append(dealVal(o));
				sb.append(", ");
				if (isTruncate(sb.length())) break;
			}
			if (sb.length() >= 2) sb.delete(sb.length() - 2, sb.length());
			sb.append(']');
			return sb.toString();
		}
		if (val instanceof Map) {
			StringBuilder sb = new StringBuilder();
			sb.append('{');
			for (Entry entry : (Set<Entry>) ((Map) val).entrySet()) {
				sb.append(entry.getKey());
				sb.append('=');
				sb.append(dealVal(entry.getValue()));
				if (isTruncate(sb.length())) break;
			}
			sb.append('}');
			setColor(Syntax.c_map);
			return sb.toString();
		}

		String text = type == String.class && val != null ? '"' + (String) val + '"' : String.valueOf(val);
		setColor(val == null ? Syntax.c_objects
		 : type == String.class ? Syntax.c_string
		 : Number.class.isAssignableFrom(box(type)) ? JSFunc.c_number
		 : Color.white);
		if (isTruncate(text.length())) {
			text = text.substring(0, 2000) + "  ...";
		}
		return text;
	}
	private boolean isTruncate(int length) {
		return enableTruncate && truncate_text.enabled() && length > 2000;
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
			Time.runTask(0, this::showNewInfo);
		});
	}
	private void showNewInfo() {
		if (val != null) {
			showNewInfo(val, val.getClass());
		} else {
			showNewInfo(null, type);
		}
	}

	private void showNewInfo(Object val1, Class<?> type) {
		Vec2 pos = getAbsPos(this);
		try {
			JSFunc.showInfo(val1, type).setPosition(pos);
		} catch (Throwable e) {
			IntUI.showException(e).setPosition(pos);
		}
	}
	public void set(Field field, Object obj) {
		this.field = field;
		isStatic = Modifier.isStatic(field.getModifiers());
		this.obj = obj;
	}


	boolean shown = E_JSFuncDisplay.value.enabled();
	public float getWidth() {
		return shown ? super.getWidth() : 0;
	}
	public void draw() {
		if (shown) super.draw();
	}


	/* private boolean isDisposed = false;
	public void dispose() {
		if (isDisposed) return;
		isDisposed = true;
		Pools.freeAll(list, true);
	}
	public boolean isDisposed() {
		return isDisposed;
	} */
}
