package modtools.ui.comp.utils;

import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.TextureRegion;
import arc.math.geom.Vec2;
import arc.scene.Element;
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
import modtools.ui.comp.input.JSRequest;
import modtools.ui.comp.input.highlight.Syntax;
import modtools.ui.comp.review.*;
import modtools.ui.content.ui.*;
import modtools.ui.content.world.Selection;
import modtools.ui.gen.HopeIcons;
import modtools.ui.menu.*;
import modtools.utils.*;
import modtools.utils.SR.CatchSR;
import modtools.utils.reflect.*;
import modtools.utils.ui.FormatHelper;

import java.lang.reflect.Array;
import java.util.Map;

import static modtools.events.E_JSFunc.truncate_text;
import static modtools.jsfunc.type.CAST.box;
import static modtools.ui.Contents.selection;
import static modtools.ui.IntUI.*;
import static modtools.utils.Tools.Sr;

@SuppressWarnings("SizeReplaceableByIsEmpty")
public abstract class ValueLabel extends ElementInlineLabel {
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
			ReviewElement.addFocusSource(this, () -> ElementUtils.findWindow(this),
			 () -> val instanceof Element ? (Element) val : null);
		} else if (Cell.class.isAssignableFrom(type)) {
			ReviewElement.addFocusSource(this, () -> ElementUtils.findWindow(this),
			 () -> val instanceof Cell<?> cell ? cell.get() : null);
		}

		Selection.addFocusSource(this, () -> val);

		MenuBuilder.addShowMenuListenerp(this, this::getMenuLists);
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
		return DisabledList.withd("elem.details", Icon.craftingSmall, "Elem Details", () -> element == null,
		 () -> new ElementDetailsWindow(element));
	}
	public static <T> MenuItem newDetailsMenuList(Element el, T val, Class<T> type) {
		return DisabledList.withd("details", Icon.infoCircleSmall, "@details",
		 () -> type.isPrimitive() && val == null,
		 () -> showNewInfo(el, val, type));
	}

	public long getOffset() {
		throw new UnsupportedOperationException("Not implemented yet");
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
		setText0(Tools.clName(val));
	}
	private void resetFunc() {
		func = defFunc;
	}

	public void layout() {
		super.layout();
	}

	public CharSequence dealVal(Object val) {
		CharSequence text = dealVal0(val);
		addText(text, color);
		return text;
	}

	IntMap<Color> colorMap = new IntMap<>();
	@SuppressWarnings("ConstantConditions")
	public CharSequence dealVal0(Object val) {
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

			addText(sb, Syntax.c_map);
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
			} catch (ArcRuntimeException ignored) {
				break iter;
			} catch (Throwable e) {
				if (DEBUG) Log.err(e);
				sb.append("▶ERROR◀");
			}
			if (checkTail && sb.length() >= 2) sb.delete(sb.length() - 2, sb.length());
			sb.append(']');
			addText(sb, Color.white);
			return sb;
		}

		String text = CatchSR.apply(() ->
		 CatchSR.of(() ->
			 val instanceof String ? '"' + (String) val + '"'
				: val instanceof Character ? STR."'\{val}'" /* + (int) (Character) val */
				: val instanceof Float ? FormatHelper.fixed((float) val, 2)
				: val instanceof Double ? FormatHelper.fixed((double) val, 2)

				: val instanceof Element ? ElementUtils.getElementName((Element) val)
				: StringUtils.getUIKey(val))
			.get(() -> String.valueOf(val))
			.get(() -> Tools.clName(val) + "@" + Integer.toHexString(val.hashCode()))
			.get(() -> Tools.clName(val))
		);
		text = truncate(text);

		Color mainColor = val == null ? Syntax.c_objects
		 : type == String.class || val instanceof Character ? Syntax.c_string
		 : Number.class.isAssignableFrom(box(type)) ? Syntax.c_number
		 : val.getClass().isEnum() ? c_enum
		 : box(val.getClass()) == Boolean.class ? Syntax.c_objects
		 : Color.white;

		addText(text, mainColor);

		return text;
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
	public abstract void flushVal();
	/** 这可能会设置字段值 */
	public void setNewVal(Object newVal) { }

	public void setVal(Object newVal) {
		try {
			setVal1(valueFunc.get(newVal));
		} catch (Throwable th) {
			Log.err(th);
			setVal1(newVal);
		}
	}
	private void setVal1(Object val) {
		if (this.val == val && (type.isPrimitive() || Reflect.isWrapper(type) || type == String.class)) return;
		if (this.val != null && val != null &&
		    this.val.getClass() == Vec2.class && val.getClass() == Vec2.class &&
		    this.val.equals(val)) return;

		this.val = val;
		if (afterSet != null) afterSet.run();
		if (func == null) resetFunc();
		try {
			context(func == defFunc ? this : null, () -> func.get(val));
		} catch (Throwable th) {
			resolveThrow(val, th);
		}
		invalidateHierarchy();
		layout();
	}
	private Label context;
	public void context(ValueLabel label, Prov<CharSequence> prov) {
		context = label;
		CharSequence text = prov.get();
		if (context != this || true) {
			setText0(text);
		}
		context = null;
	}
	public void addText(CharSequence text, Color color) {
		setColor(color);
		// if (context == this) super.addText(text, color);
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
					 CatchSR.of(() -> StringUtils.getUIKey(fieldVal))
						.get(() -> String.valueOf(fieldVal))
					);
					builder.append(STR."\t\{field.getName()} = \{uiKey};\n");
				});
				builder.append("}}");
				JSFunc.copyText(builder);
			}));
			list.add(DisabledList.withd("style.set", Icon.copySmall, "Set Style", () -> val == null, () -> {
				IntUI.showSelectListTable(this,
				 Seq.with(ShowUIList.styleKeyMap.keySet())
					.retainAll(type::isInstance),
				 () -> (Style) val, this::setNewVal,
				 s -> StringUtils.fieldFormat(ShowUIList.styleKeyMap.get(s)),
				 Float.NEGATIVE_INFINITY, 32,
				 true, Align.top);
			}));
		}

		list.add(MenuItem.with("func.stringify", Icon.diagonalSmall, "StringifyFunc", () -> {
			JSRequest.<Func<Object, CharSequence>>requestForDisplay(defFunc,
			 getObject(), o -> func = o);
		}));

		list.add(MenuItem.with("clear", Icon.eraserSmall, "@clear", this::clearVal));
		/* list.add(MenuItem.with("truncate", Icon.listSmall, () -> STR."\{enableTruncate ? "Disable" : "Enable"} Truncate", () -> {
			enableTruncate = !enableTruncate;
		})); */

		if (enabledUpdateMenu()) {
			CheckboxList checkboxList = CheckboxList.withc("autoRefresh", Icon.refresh1Small, "Auto Refresh", enableUpdate, () -> {
				enableUpdate = !enableUpdate;
			});
			list.add(checkboxList);
		}
		list.add(MenuBuilder.copyAsJSMenu("value", () -> val));
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
			 elementSetter(list, this::setVal1);
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
	public boolean readOnly() {
		return true;
	}
}