package modtools.ui.components;

import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.style.Drawable;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Cell;
import arc.struct.*;
import arc.util.*;
import mindustry.Vars;
import mindustry.entities.Effect;
import mindustry.gen.*;
import modtools.events.*;
import modtools.ui.*;
import modtools.ui.HopeIcons;
import modtools.ui.components.input.*;
import modtools.ui.components.input.highlight.Syntax;
import modtools.ui.content.ui.ReviewElement;
import modtools.ui.content.ui.ReviewElement.*;
import modtools.utils.*;
import modtools.utils.SR.CatchSR;
import modtools.utils.reflect.FieldUtils;

import java.lang.reflect.*;
import java.util.*;
import java.util.Map.Entry;

import static modtools.events.E_JSFunc.truncate_text;
import static modtools.ui.Contents.selection;
import static modtools.ui.IntUI.*;
import static modtools.utils.Tools.*;

public class ValueLabel extends MyLabel {
	public static Object unset = new Object();

	public static Color c_enum = new Color(0xFFC66DFF);
	// public static final String mark_c_enum    = "[#FFC66DFF]";
	// public static final String mark_lightgray = "[lightgray]";
	//
	// /** @see Syntax#c_map */
	// public static final
	// String mark_c_map = "[#" + Syntax.c_map + "]",
	//  mark_c_objects   = "[#" + Syntax.c_objects + "]",
	//  mark_c_string    = "[#" + Syntax.c_string + "]",
	//  mark_c_number    = "[#" + Syntax.c_number + "]";

	public final int truncateLength = 2000;

	public            Object   val;
	public @Nullable  Object   obj;
	private @Nullable Field    field;
	public final      Class<?> type;

	/** 是否启用截断文本（当文本过长时，容易内存占用过大） */
	public boolean
	 enableTruncate = true,
	/** 是否启用更新 */
	enableUpdate = true;

	public final Func<Object, CharSequence> defFunc   = this::dealVal;
	/**
	 * <p>用于显示label内容</p>
	 * <p>每次修改时，都会执行这个func，返回值作为显示值</p>
	 */
	public       Func<Object, CharSequence> func;
	/**
	 *
	 */
	public       Func<Object, Object>       valueFunc = o -> o;


	public ValueLabel(Object newVal, Object obj, Method method) {
		this(newVal, method.getReturnType(), null, obj, method);
		labelAlign = Align.left;
		lineAlign = Align.topLeft;
		isStatic = Modifier.isStatic(method.getModifiers());
	}
	public ValueLabel(Object newVal, Class<?> type, Field field, Object obj) {
		this(newVal, type, field, obj, null);
	}
	public ValueLabel(Object newVal, Class<?> type, Field field, Object obj, Method method) {
		super((CharSequence) null);
		if (type == null) throw new NullPointerException("'type' is null.");
		if (newVal != null && newVal != unset && !type.isPrimitive() && !type.isInstance(newVal))
			throw new IllegalArgumentException("type(" + type + ") mismatches value(" + newVal + ").");
		// markupEnabled = true;
		wrap = true;
		setStyle(IntStyles.MOMO_LabelStyle);
		this.type = type;
		if (field != null) set(field, obj);
		if (newVal != unset) setVal0(newVal);
		setAlignment(Align.left, Align.left);

		update(() -> {
			if (E_JSFunc.auto_refresh.enabled() && field != null && enableUpdate) {
				setVal();
			}
		});
		MyEvents.on(E_JSFuncDisplay.value, b -> shown = b.enabled());

		if (Element.class.isAssignableFrom(type) && val instanceof Element) {
			ReviewElement.addFocusSource(this, () -> ElementUtils.getWindow(this), () -> (Element) val);
		} else if (Cell.class.isAssignableFrom(type)) {
			ReviewElement.addFocusSource(this, () -> ElementUtils.getWindow(this), () -> val == null ? null : ((Cell<?>) val).get());
		}

		IntUI.addShowMenuListener(this, () -> getMenuLists(type, field, obj));
	}
	public Seq<MenuList> getMenuLists(Class<?> type, Field field, Object obj) {
		Seq<MenuList> list = new Seq<>();
		Sr(type).isExtend(__ -> {
			 list.add(MenuList.with(Icon.imageSmall, "img", () -> {
				 SR.catchSatisfy(() -> Sr(val)
					.isInstance(TextureRegion.class, JSFunc::dialog)
					.isInstance(Texture.class, JSFunc::dialog)
					.isInstance(Drawable.class, JSFunc::dialog)
				 );
			 }));
		 }, TextureRegion.class, Texture.class, Drawable.class)
		 .isExtend(__ -> {
			 list.add(MenuList.with(Icon.zoomSmall, Contents.review_element.localizedName(), () -> {
				 JSFunc.reviewElement((Element) val);
			 }));
			 list.add(newElementDetailsList((Element) val));
		 }, Element.class)
		 .isExtend(__ -> {
			 list.add(MenuList.with(Icon.infoSmall, "at player", () -> {
				 ((Effect) val).at(Vars.player);
			 }));
		 }, Effect.class)
		 .isExtend(__ -> {
			 list.add(MenuList.with(Icon.infoCircleSmall, "cell details", b -> {
				 new CellDetailsWindow((Cell<?>) val).setPosition(ElementUtils.getAbsPos(b)).show();
			 }));
		 }, Cell.class)
		 .isExtend(__ -> {
			 list.add(DisabledList.withd(HopeIcons.position,
				(val == null ? "" : selection.focusInternal.contains(val) ? "hide" : "show")
				+ " on world", () -> val == null, () -> {
					if (!selection.focusInternal.add(val)) selection.focusInternal.remove(val);
				}));
		 }, Building.class, Unit.class, Bullet.class);

		if (field != null && !type.isPrimitive()) list.add(MenuList.with(Icon.editSmall, "@selection.reset", () -> {
			JSRequest.requestForField(val, obj, o -> {
				setFieldValue(type.cast(o));
			});
		}));

		list.add(newDetailsMenuList(this, val, (Class) type));
		list.add(MenuList.with(Icon.listSmall, () -> (enableTruncate ? "disable" : "enable") + " truncate", () -> {
			enableTruncate = !enableTruncate;
		}));
		list.add(MenuList.with(Icon.eyeSmall, "stringifyFunc", () -> {
			JSRequest.<Func<Object, CharSequence>>requestForDisplay(defFunc, obj, o -> func = o);
		}));
		list.add(MenuList.with(Icon.eyeSmall, "valueFunc", () -> {
			JSRequest.<Func<Object, Object>>requestForDisplay(func, obj, o -> valueFunc = o);
		}));
		list.add(MenuList.with(Icon.eraserSmall, "@clear", this::clearVal));

		// Log.info("valuelabel: @", enableUpdate);
		CheckboxList checkboxList = CheckboxList.withc(Icon.refresh1Small, "auto refresh", enableUpdate, () -> {
			enableUpdate = !enableUpdate;
		});
		list.add(checkboxList);
		list.add(copyAsJSMenu("value", () -> val));
		return list;
	}
	public static MenuList newElementDetailsList(Element element) {
		return MenuList.with(Icon.crafting, "el details", () -> {
			new ElementDetailsWindow(element);
		});
	}
	public static <T> MenuList newDetailsMenuList(Element el, T val, Class<T> type) {
		return MenuList.with(Icon.infoCircleSmall, "@details", () -> {
			showNewInfo(el, val, type);
		});
	}
	private Long    offset;
	private boolean isStatic;
	public boolean isStatic() {
		return isStatic;
	}
	public long getOffset() {
		if (field == null) throw new RuntimeException("field is null");
		if (offset == null) offset = FieldUtils.fieldOffset(field);
		return offset;
	}
	public float getPrefWidth() {
		if (prefSizeInvalid) computePrefSize();
		return getLastSize().x;
	}
	public float getPrefHeight() {
		if (prefSizeInvalid) computePrefSize();
		return getLastSize().y;
	}
	// Method m = Label.class.getDeclaredMethod("scaleAndComputePrefSize");
	void computePrefSize() {
		prefSizeInvalid = true;
		Reflect.invoke(Label.class, this, "scaleAndComputePrefSize", null);
		prefSizeInvalid = false;
		wrap = false;
		getLastSize().x = Mathf.clamp(super.getPrefWidth(), 80, 1000);
		getLastSize().y = Math.max(Math.max(super.getPrefHeight(), getHeight()), 40);
		wrap = true;
	}
	private Vec2 lastSize;
	public Vec2 getLastSize() {
		if (lastSize == null) lastSize = new Vec2();
		return lastSize;
	}
	private void resThrowable(Object val, Throwable th) {
		Log.err(th);
		IntUI.showException(th);
		if (func != defFunc) {
			resetFunc();
			try {
				setText(defFunc.get(val));
				return;
			} catch (Throwable ignored) {}
		}
		setText(val.getClass().getName());
	}
	private void resetFunc() {
		func = defFunc;
	}

	@SuppressWarnings("ConstantConditions")
	public CharSequence dealVal(Object val) {
		if (val instanceof ObjectMap) {
			StringBuilder sb = new StringBuilder();
			sb.append('{');
			boolean checkTail = false;
			for (var o : ((ObjectMap<?, ?>) val)) {
				sb.append(dealVal(o.key));
				sb.append('=');
				sb.append(dealVal(o.value));
				sb.append(", ");
				checkTail = true;
				if (isTruncate(sb.length())) break;
			}
			if (checkTail) sb.deleteCharAt(sb.length() - 2);
			sb.append('}');

			// return mark_c_map + sb;
			setColor(Syntax.c_map);
			return sb;
		}
		if (val instanceof Map) {
			StringBuilder sb = new StringBuilder();
			sb.append('{');
			boolean checkTail = false;
			for (var entry : (Set<Entry>) ((Map) val).entrySet()) {
				sb.append(entry.getKey());
				sb.append('=');
				sb.append(dealVal(entry.getValue()));
				sb.append(", ");
				checkTail = true;
				if (isTruncate(sb.length())) break;
			}
			if (checkTail) sb.deleteCharAt(sb.length() - 2);
			sb.append('}');

			setColor(Syntax.c_map);
			return sb;
		}
		if ((field == null || !field.getName().startsWith("entries"))
				&& (val instanceof Iterable || (val != null && val.getClass().isArray()))) {
			boolean       checkTail = false;
			StringBuilder sb        = new StringBuilder();
			sb.append('[');
			l:
			try {
				var seq = val instanceof Iterable<?> ? Seq.with((Iterable<?>) val) :
				 Seq.with(asArray(val));
				if (seq.any()) {
					Object last  = seq.first();
					int    count = 0;
					for (Object item : seq) {
						if (last == item) {
							count++;
						} else {
							sb.append(dealVal(last));
							if (count > 1) sb.append(" ▶×").append(count).append("◀");
							sb.append(", ");
							if (isTruncate(sb.length())) break;
							last = item;
							count = 0;
						}
					}
					sb.append(dealVal(last));
					if (count > 1) sb.append(" ▶×").append(count).append("◀");
					sb.append(", ");
					// break l;
					checkTail = true;
				}
				/* for (Object o : seq) {
					sb.append(dealVal(o));
					sb.append(", ");
					if (isTruncate(sb.length())) break;
				} */
				/* for (int i = 0, len = Array.getLength(val); i < len; i++) {
					sb.append(dealVal(Array.get(val, i)));
					sb.append(", ");
					if (isTruncate(sb.length())) break;
				} */
			} catch (ArcRuntimeException ignored) {
			} catch (Throwable e) {sb.append("▶ERROR◀");}
			if (checkTail && sb.length() >= 2) sb.delete(sb.length() - 2, sb.length());
			sb.append(']');
			return sb;
		}

		String text = CatchSR.apply(() ->
		 CatchSR.of(() ->
			 val instanceof String ? '"' + (String) val + '"'
				: val instanceof Character ? "'" + val + "'"
				: val instanceof Float ? Strings.autoFixed((float) val, 2)
				: String.valueOf(val))
			.get(() -> val.getClass().getName() + "@" + val.hashCode())
			.get(() -> val.getClass().getName())
		);
		text = truncate(text);

		Color mainColor = val == null ? Syntax.c_objects
		 : type == String.class || val instanceof Character ? Syntax.c_string
		 : Number.class.isAssignableFrom(box(type)) ? Syntax.c_number
		 : val.getClass().isEnum() ? c_enum
		 : Color.white;
		setColor(mainColor);

		return text;
	}
	private boolean isTruncate(int length) {
		return enableTruncate && truncate_text.enabled() && length > truncateLength;
	}
	private String truncate(String text) {
		return isTruncate(text.length()) ? text.substring(0, truncateLength) + "  ..." : text;
	}
	private Object[] asArray(Object arr) {
		if (arr instanceof Object[]) return (Object[]) arr;
		int      len    = Array.getLength(arr);
		Object[] objArr = new Object[len];
		for (int i = 0; i < len; i++) {
			objArr[i] = Array.get(arr, i);
		}
		return objArr;
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

	public void clearVal() {
		val = "";
		super.setText((CharSequence) null);
		prefSizeInvalid = true;
	}
	public void setText(CharSequence newText) {
		if (newText == null || /* newText.isEmpty()新版本才有 */newText.length() == 0) {
			newText = "<EMPTY>";
			setColor(Color.gray);
		}
		super.setText(newText);
	}
	public void setVal() {
		if (field == null || (obj == null && !isStatic)) {
			setVal0(null);
		} else {
			Object value = FieldUtils.getFieldValue(isStatic ? field.getDeclaringClass() : obj, getOffset(), field.getType());
			setVal0(value);
		}
	}
	private void setVal0(Object newVal) {
		try {
			setVal(valueFunc.get(newVal));
		} catch (Throwable th) {
			Log.err(th);
			setVal(newVal);
		}
	}
	public void setVal(Object val) {
		if (this.val == val && (type.isPrimitive() || type == String.class)) return;
		this.val = val;
		if (func == null) resetFunc();
		try {
			setText(func.get(val));
		} catch (Throwable th) {
			resThrowable(val, th);
		}
		layout();
	}

	private static void showNewInfo(Element el, Object val1, Class<?> type) {
		Vec2 pos = ElementUtils.getAbsPos(el);
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


	/* public Element build() {
		if (val instanceof Iterable ite) {
			Table table = new Table();
			for (Object newV : ite) {
				table.add(new ValueLabel(newV, Kit.classOrNull(Vars.mods.mainLoader(), ite.getClass().getTypeParameters()[0].getName()), null, null));
			}
			return table;
		}
		return this;
	} */

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

