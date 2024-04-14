package modtools.ui.components.utils;

import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.style.*;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Cell;
import arc.struct.*;
import arc.util.*;
import mindustry.Vars;
import mindustry.entities.Effect;
import mindustry.gen.*;
import modtools.events.*;
import modtools.jsfunc.*;
import modtools.ui.*;
import modtools.ui.components.input.*;
import modtools.ui.components.input.highlight.Syntax;
import modtools.ui.components.review.*;
import modtools.ui.content.ui.*;
import modtools.ui.content.world.Selection;
import modtools.ui.gen.HopeIcons;
import modtools.ui.menu.*;
import modtools.utils.*;
import modtools.utils.SR.CatchSR;
import modtools.utils.reflect.*;
import modtools.utils.ui.FormatHelper;

import java.lang.reflect.*;
import java.util.Map;

import static modtools.events.E_JSFunc.truncate_text;
import static modtools.jsfunc.type.CAST.box;
import static modtools.ui.Contents.selection;
import static modtools.ui.IntUI.*;
import static modtools.utils.Tools.*;

@SuppressWarnings("SizeReplaceableByIsEmpty")
public abstract class ValueLabel extends NoMarkupLabel {
	public static Object unset          = new Object();
	public static Color  c_enum         = new Color(0xFFC66DFF);
	public final  int    truncateLength = 2000;

	public static final boolean DEBUG = false;

	public       Object   val;
	public final Class<?> type;
	protected ValueLabel(Class<?> type) {
		super((CharSequence) null);
		if (type == null) throw new NullPointerException("'type' is null.");
		this.type = type;
		wrap = true;
		setStyle(HopeStyles.defaultLabel);
		setAlignment(Align.left, Align.left);

		MyEvents.on(E_JSFuncDisplay.value, b -> shown = b.enabled());

		if (Element.class.isAssignableFrom(type) || val instanceof Element) {
			ReviewElement.addFocusSource(this, () -> ElementUtils.getWindow(this),
			 () -> val instanceof Element ? (Element) val : null);
		} else if (Cell.class.isAssignableFrom(type)) {
			ReviewElement.addFocusSource(this, () -> ElementUtils.getWindow(this),
			 () -> val instanceof Cell<?> cell ? cell.get() : null);
		}

		// while
		// if (Entityc.class.isAssignableFrom(type) || val instanceof Entityc
		// 		|| Entityc[].class.isAssignableFrom(type) || val instanceof Entityc[]) {
		Selection.addFocusSource(this, () -> val);
		// }

		IntUI.addShowMenuListenerp(this, this::getMenuLists);
	}

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
	public       Func<Object, Object>       valueFunc = o -> o;

	public abstract Seq<MenuItem> getMenuLists();
	public static MenuItem newElementDetailsList(Element element) {
		return DisabledList.withd("elem.details", Icon.crafting, "Elem Details", () -> element == null,
		 () -> new ElementDetailsWindow(element));
	}
	public static <T> MenuItem newDetailsMenuList(Element el, T val, Class<T> type) {
		return DisabledList.withd("details", Icon.infoCircleSmall, "@details",
		 () -> type.isPrimitive() && val == null,
		 () -> showNewInfo(el, val, type));
	}

	protected boolean isStatic;
	public boolean isStatic() {
		return isStatic;
	}
	public long getOffset() {
		throw new UnsupportedOperationException("Not implemented yet");
	}
	/* public float getPrefWidth() {
		if (prefSizeInvalid) computePrefSize();
		return getLastSize().x;
	}
	public float getPrefHeight() {
		if (prefSizeInvalid) computePrefSize();
		return getLastSize().y;
	} */
	// Method m = Label.class.getDeclaredMethod("scaleAndComputePrefSize");
	void computePrefSize() {
		prefSizeInvalid = true;
		wrap = true;
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
	private void resolveThrow(Object val, Throwable th) {
		Log.err(th);
		IntUI.showException(th);
		if (func != defFunc) {
			resetFunc();
			try {
				setText0(defFunc.get(val));
				return;
			} catch (Throwable ignored) { }
		}
		setText0(val.getClass().getName());
	}
	private void resetFunc() {
		func = defFunc;
	}

	@SuppressWarnings("ConstantConditions")
	public CharSequence dealVal(Object val) {
		if (val instanceof ObjectMap || val instanceof Map) {
			StringBuilder sb = new StringBuilder();
			sb.append('{');
			boolean checkTail = false;
			if (val instanceof ObjectMap<?, ?> map) for (var o : map) {
				appendMap(sb, o.key, o.value);
				checkTail = true;
				if (isTruncate(sb.length())) break;
			}
			else for (var entry : ((Map<?, ?>) val).entrySet()) {
				appendMap(sb, entry.getKey(), entry.getValue());
				checkTail = true;
				if (isTruncate(sb.length())) break;
			}
			if (checkTail) sb.deleteCharAt(sb.length() - 2);
			sb.append('}');

			setColor(Syntax.c_map);
			return sb;
		}
		iter:
		if ((val instanceof Iterable || (val != null && val.getClass().isArray()))) {
			boolean       checkTail = false;
			StringBuilder sb        = new StringBuilder();
			sb.append('[');
			try {
				var seq = val instanceof Iterable<?> ? Seq.with((Iterable<?>) val) :
				 Seq.with(asArray(val));
				if (seq.any()) {
					Object last  = seq.first();
					int    count = 0;
					for (Object item : seq) {
						if (last != null && Reflect.isWrapper(last.getClass())
						 ? last.equals(item) : last == item) {
							count++;
						} else {
							sb.append(dealVal(last));
							if (count > 1) sb.append(" ▶×").append(count).append("◀");
							sb.append(getArrayDelimiter());
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
				break iter;
			} catch (Throwable e) {
				if (DEBUG) Log.err(e);
				sb.append("▶ERROR◀");
			}
			if (checkTail && sb.length() >= 2) sb.delete(sb.length() - 2, sb.length());
			sb.append(']');
			return sb;
		}

		String text = CatchSR.apply(() ->
		 CatchSR.of(() ->
			 val instanceof String ? '"' + (String) val + '"'
				: val instanceof Character ? STR."'\{val}'" /* + (int) (Character) val */
				: val instanceof Float ? FormatHelper.fixed((float) val, 2)
				: val instanceof Double ? FormatHelper.fixed((double) val, 2)

				: val instanceof Element ? ElementUtils.getElementName((Element) val)
				: getUIKey(val))
			.get(() -> String.valueOf(val))
			.get(() -> val.getClass().getName() + "@" + Integer.toHexString(val.hashCode()))
			.get(() -> val.getClass().getName())
		);
		text = truncate(text);

		Color mainColor = val == null ? Syntax.c_objects
		 : type == String.class || val instanceof Character ? Syntax.c_string
		 : Number.class.isAssignableFrom(box(type)) ? Syntax.c_number
		 : val.getClass().isEnum() ? c_enum
		 : box(val.getClass()) == Boolean.class ? Syntax.c_objects
		 : Color.white;
		setColor(mainColor);

		return text;
	}
	private static final boolean withPrefix = true;
	public static String getUIKey(Object val) {
		return val instanceof Drawable icon && ShowUIList.iconKeyMap.containsKey(icon) ?
		 (withPrefix ? "Icon." : "") + ShowUIList.iconKeyMap.get(icon)

		 : val instanceof Drawable drawable && ShowUIList.styleIconKeyMap.containsKey(drawable) ?
		 (withPrefix ? "Styles." : "") + ShowUIList.styleIconKeyMap.get(drawable)

		 : val instanceof Drawable drawable && ShowUIList.texKeyMap.containsKey(drawable) ?
		 (withPrefix ? "Tex." : "") + ShowUIList.texKeyMap.get(drawable)

		 : val instanceof Style s && ShowUIList.styleKeyMap.containsKey(s) ?
		 (withPrefix ? "Styles." : "") + ShowUIList.styleKeyMap.get(s)

		 : val instanceof Color c && ShowUIList.colorKeyMap.containsKey(c) ?
		 ShowUIList.colorKeyMap.get(c)

		 : val instanceof Group g && ShowUIList.uiKeyMap.containsKey(g) ?
		 (withPrefix ? "Vars.ui." : "") + ShowUIList.uiKeyMap.get(g)

		 : val instanceof Font f && ShowUIList.fontKeyMap.containsKey(f) ?
		 (withPrefix ? "Fonts." : "") + ShowUIList.fontKeyMap.get(f)

		 : Tools._throw();
	}
	private void appendMap(StringBuilder sb, Object key, Object value) {
		sb.append(dealVal(key));
		sb.append('=');
		sb.append(dealVal(value));
		sb.append(getArrayDelimiter());
	}
	private static String getArrayDelimiter() {
		return E_JSFunc.array_delimiter.getString();
	}

	private boolean isTruncate(int length) {
		return enableTruncate && truncate_text.enabled() && length > truncateLength;
	}
	private String truncate(String text) {
		//noinspection StringTemplateMigration
		return isTruncate(text.length()) ? text.substring(0, truncateLength) + "  ..." : text;
	}
	public static final Object[] EMPTY_ARRAY = new Object[0];
	private Object[] asArray(Object arr) {
		if (arr instanceof Object[]) return (Object[]) arr;
		int len = Array.getLength(arr);
		if (len == 0) return EMPTY_ARRAY;
		Object[] objArr = new Object[len];
		for (int i = 0; i < len; i++) {
			objArr[i] = Array.get(arr, i);
		}
		return objArr;
	}

	public void clearVal() {
		val = "";
		super.setText((CharSequence) null);
		prefSizeInvalid = true;
	}
	public static final String ERROR = "<ERROR>";
	public void setError() {
		super.setText(ERROR);
		setColor(Color.red);
	}
	public void setText(CharSequence newText) {
		throw new UnsupportedOperationException("the ValueLabel cannot be set by setText(newText)");
	}
	void setText0(CharSequence newText) {
		if (newText == null || newText.length() == 0) {
			newText = "<EMPTY>";
			setColor(Color.gray);
		}
		super.setText(newText);
	}
	public Runnable afterSet;
	public abstract void setVal();
	/** 这可能会设置字段值 */
	public void setNewVal(Object newVal) { }

	protected void setVal0(Object newVal) {
		try {
			setVal(valueFunc.get(newVal));
		} catch (Throwable th) {
			Log.err(th);
			setVal(newVal);
		}
	}
	public void setVal(Object val) {
		if (this.val == val && (type.isPrimitive() || Reflect.isWrapper(type) || type == String.class)) return;
		if (this.val != null && val != null &&
				this.val.getClass() == Vec2.class && val.getClass() == Vec2.class &&
				this.val.equals(val)) return;

		this.val = val;
		if (afterSet != null) afterSet.run();
		if (func == null) resetFunc();
		try {
			setText0(func.get(val));
		} catch (Throwable th) {
			resolveThrow(val, th);
		}
		invalidateHierarchy();
		layout();
	}

	private static void showNewInfo(Element el, Object val1, Class<?> type) {
		Vec2 pos = ElementUtils.getAbsolutePos(el);
		try {
			INFO_DIALOG.showInfo(val1, val1 != null ? val1.getClass() : type).setPosition(pos);
		} catch (Throwable e) {
			IntUI.showException(e).setPosition(pos);
		}
	}

	protected Seq<MenuItem> basicMenuLists(Seq<MenuItem> list) {
		specialBuild(list);
		detailsBuild(list);

		if (Style.class.isAssignableFrom(type)) {
			list.add(DisabledList.withd("style.copy", Icon.copySmall, "Copy Style", () -> val == null, () -> {
				Class<?>      cls     = val.getClass();
				StringBuilder builder = new StringBuilder(STR."new \{ClassUtils.getSuperExceptAnonymous(cls).getSimpleName()}(){{\n");
				ClassUtils.walkPublicNotStaticKeys(cls, field -> {
					Object fieldVal = FieldUtils.getOrNull(field, val);
					if (fieldVal == null || (fieldVal instanceof Number n && n.intValue() == 0)) return;
					String uiKey = CatchSR.apply(() ->
					 CatchSR.of(() -> getUIKey(fieldVal))
						.get(() -> String.valueOf(fieldVal))
					);
					builder.append(STR."\t\{field.getName()} = \{uiKey};\n");
				});
				builder.append("}}");
				JSFunc.copyText(builder);
			}));
		}

		list.add(MenuItem.with("func.stringify", Icon.diagonalSmall, "StringifyFunc", () -> {
			JSRequest.<Func<Object, CharSequence>>requestForDisplay(defFunc,
			 getObject(), o -> func = o);
		}));

		list.add(MenuItem.with("clear", Icon.eraserSmall, "@clear", this::clearVal));
		list.add(MenuItem.with("truncate", Icon.listSmall, () -> STR."\{enableTruncate ? "Disable" : "Enable"} Truncate", () -> {
			enableTruncate = !enableTruncate;
		}));

		if (enabledUpdateMenu()) {
			CheckboxList checkboxList = CheckboxList.withc("autoRefresh", Icon.refresh1Small, "Auto Refresh", enableUpdate, () -> {
				enableUpdate = !enableUpdate;
			});
			list.add(checkboxList);
		}
		list.add(copyAsJSMenu("value", () -> val));
		return list;
	}

	protected void detailsBuild(Seq<MenuItem> list) {
		list.add(newDetailsMenuList(this, val, (Class) type));
	}
	protected void specialBuild(Seq<MenuItem> list) {
		Sr(type).isExtend(cl -> {
			 if (cl == Drawable.class) addPickDrawable(list);
			 list.add(MenuItem.with("img.show", Icon.imageSmall, "img", () ->
				SR.apply(() -> Sr(val)
				 .isInstance(TextureRegion.class, INFO_DIALOG::dialog)
				 .isInstance(Texture.class, INFO_DIALOG::dialog)
				 .isInstance(Drawable.class, INFO_DIALOG::dialog)
				)));
		 }, TextureRegion.class, Texture.class, Drawable.class)
		 /* .isExtend(__ -> {
			 list.add(MenuList.with(Icon.androidSmall, "change", () -> {

			 }));
		 }, Drawable.class) */
		 .isExtend(_ -> {
			 list.add(MenuItem.with("element.inspect", Icon.zoomSmall, Contents.review_element.localizedName(), () -> {
				 REVIEW_ELEMENT.inspect((Element) val);
			 }));
			 list.add(newElementDetailsList((Element) val));
			 elementSetter(list, this::setVal);
		 }, Element.class)
		 .isExtend(_ -> {
			 list.add(MenuItem.with("effect.spawnAtPlayer", Icon.infoSmall, "At player", () -> {
				 ((Effect) val).at(Vars.player);
			 }));
		 }, Effect.class)
		 .isExtend(_ -> {
			 list.add(MenuItem.with("cell.inspect", Icon.infoCircleSmall, "Cell details", b -> {
				 new CellDetailsWindow((Cell<?>) val).setPosition(ElementUtils.getAbsolutePos(b)).show();
			 }));
		 }, Cell.class)
		 .isExtend(_ -> {
			 list.add(DisabledList.withd("selection.showOnWorld", HopeIcons.position,
				STR."\{
				 val == null ? "" : selection.focusInternal.contains(val) ? "Hide from" : "Show on"
				 } world",
				() -> val == null, () -> {
					if (!selection.focusInternal.add(val)) selection.focusInternal.remove(val);
				}));
		 }, Building.class, Unit.class, Bullet.class);
	}
	private void addPickDrawable(Seq<MenuItem> list) {
		list.add(MenuItem.with("drawable.pick", Icon.editSmall, "@pickdrawable", () -> {
			if (val instanceof Drawable d)
				IntUI.drawablePicker().show(d, true, this::setNewVal);
		}));
	}

	protected void elementSetter(Seq<MenuItem> list, Cons<Element> callback) {
		list.add(DisabledList.withd("element.pick", Icon.editSmall, "Select and Replace",
		 topGroup::isSelecting,
		 () -> topGroup.requestSelectElem(TopGroup.defaultDrawer, callback)
		));
	}

	public abstract Object getObject();
	public boolean enabledUpdateMenu() {
		return true;
	}

	boolean shown = E_JSFuncDisplay.value.enabled();
	public float getWidth() {
		return shown ? super.getWidth() : 0;
	}
	public void draw() {
		if (shown) super.draw();
	}

	boolean cleared;
	public void clear() {
		cleared = true;
		super.clear();
		clearVal();
	}
	public boolean isFinal() {
		return false;
	}
	public boolean isValid() {
		return true;
	}
}