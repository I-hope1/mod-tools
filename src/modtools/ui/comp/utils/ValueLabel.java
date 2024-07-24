package modtools.ui.comp.utils;

import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.GlyphLayout.GlyphRun;
import arc.graphics.g2d.TextureRegion;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.layout.Cell;
import arc.struct.*;
import arc.util.*;
import mindustry.Vars;
import mindustry.entities.Effect;
import mindustry.gen.*;
import modtools.events.*;
import modtools.jsfunc.*;
import modtools.ui.*;
import modtools.ui.comp.input.NoMarkupLabel;
import modtools.ui.comp.input.highlight.*;
import modtools.ui.comp.review.*;
import modtools.ui.content.ui.*;
import modtools.ui.content.world.Selection;
import modtools.ui.gen.HopeIcons;
import modtools.ui.menu.*;
import modtools.utils.*;
import modtools.utils.reflect.*;
import modtools.utils.ui.FormatHelper;

import java.lang.reflect.Array;
import java.util.Map;

import static modtools.events.E_JSFunc.truncate_text;
import static modtools.jsfunc.type.CAST.box;
import static modtools.ui.Contents.selection;
import static modtools.ui.IntUI.topGroup;

@SuppressWarnings({"SizeReplaceableByIsEmpty"})
public abstract class ValueLabel extends NoMarkupLabel {
	public static Object unset          = new Object();
	public static Color  c_enum         = new Color(0xFFC66D_FF);
	public final  int    truncateLength = 1000;

	public static final boolean DEBUG = false;

	public Object   val;
	public Class<?> type;
	private ValueLabel() {
		super((CharSequence) null);
		type = null;
	}
	protected ValueLabel(Class<?> type) {
		super((CharSequence) null);
		if (type == null) throw new NullPointerException("'type' is null.");
		this.type = type;
		wrap = true;
		setStyle(HopeStyles.defaultLabel);
		setAlignment(Align.left, Align.left);

		MyEvents.on(E_JSFuncDisplay.value, b -> shown = b.enabled());

		addFocusListener();

		Object[] val = {null};
		addListener(new InputListener() {
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				int    cursor    = getCursor(x, y);
				Object bestSoFar = null;
				for (int i = 0; i < cursor; i++) {
					if (!startIndexMap.containsKey(i)) continue;
					Object o       = startIndexMap.get(i);
					int    toIndex = endIndexMap.get(o);
					if (toIndex > cursor && o != null) bestSoFar = o;
				}
				val[0] = bestSoFar;
				return false;
			}
		});
		MenuBuilder.addShowMenuListenerp(this, () -> {
			Object     val0  = val[0];
			ValueLabel label = this;
			if (val0 != null) {
				Class<?> type1 = valToType.get(val0);
				Object   obj   = valToObj.get(val0);
				label = ItemValueLabel.of(obj, type1, () -> val[0]);
			}
			return label.getMenuLists();
		});
	}
	public void addFocusListener() {
		if (Element.class.isAssignableFrom(type) || val instanceof Element) {
			ReviewElement.addFocusSource(this, () -> ElementUtils.findWindow(this),
			 () -> val instanceof Element ? (Element) val : null);
		} else if (Cell.class.isAssignableFrom(type)) {
			ReviewElement.addFocusSource(this, () -> ElementUtils.findWindow(this),
			 () -> val instanceof Cell<?> cell ? cell.get() : null);
		}

		Selection.addFocusSource(this, () -> val);
	}

	/** 是否启用截断文本（当文本过长时，容易内存占用过大） */
	public boolean
	 enableTruncate = true,
	/** 是否启用更新 */
	enableUpdate = true;

	public Func<Object, Object> valueFunc = o -> o;

	public abstract Seq<MenuItem> getMenuLists();
	public static MenuItem newElementDetailsList(Element element) {
		return DisabledList.withd("elem.details", Icon.craftingSmall, "Elem Details", () -> element == null,
		 () -> new ElementDetailsWindow(element));
	}
	public static <T> MenuItem newDetailsMenuList(Element el, Prov<T> val, Class<?> type) {
		return DisabledList.withd("details", Icon.infoCircleSmall, "@details",
		 () -> type.isPrimitive() && val.get() == null,
		 () -> showNewInfo(el, val.get(), type));
	}

	public long getOffset() {
		throw new UnsupportedOperationException("Not implemented yet");
	}

	private void resolveThrow(Throwable th) {
		Log.err(th);
		IntUI.showException(th);
	}

	public void layout() {
		super.layout();
	}

	public void setAndProcessText(Object val) {
		text.setLength(0);
		startIndexMap.clear();
		endIndexMap.clear();
		appendValue(text, val);
		setColor(color);
	}

	private final IntMap<Object>              startIndexMap = new IntMap<>();
	private final ObjectMap<Object, Integer>  endIndexMap   = new ObjectMap<>();
	private final ObjectMap<Object, Class<?>> valToType     = new ObjectMap<>();
	private final ObjectMap<Object, Object>   valToObj      = new ObjectMap<>();

	@SuppressWarnings("ConstantConditions")
	private void appendValue(StringBuilder text, Object val) {
		if (val == null) {
			text.append("null");
			setColor(JSSyntax.c_keyword);
			return;
		}
		if (val instanceof ObjectMap || val instanceof Map) {
			text.append('{');
			boolean checkTail = false;
			if (val instanceof ObjectMap<?, ?> map) for (var o : map) {
				valToObj.put(o.key, val);
				valToObj.put(o.value, val);
				appendMap(text, o.key, o.value);
				checkTail = true;
				if (isTruncate(text.length())) break;
			}
			else for (var entry : ((Map<?, ?>) val).entrySet()) {
				valToObj.put(entry.getKey(), val);
				valToObj.put(entry.getValue(), val);
				appendMap(text, entry.getKey(), entry.getValue());
				checkTail = true;
				if (isTruncate(text.length())) break;
			}
			if (checkTail) text.deleteCharAt(text.length() - 2);
			text.append('}');

			setColor(Syntax.c_map);
			return;
		}
		iter:
		if ((val instanceof Iterable || (val != null && val.getClass().isArray()))) {
			boolean checkTail = false;
			text.append('[');
			try {
				var seq = val instanceof Iterable<?> ? Seq.with((Iterable<?>) val) :
				 Seq.with(asArray(val));
				if (seq.any()) {
					Object last  = seq.first();
					int    count = 0;
					for (Object item : seq) {
						valToObj.put(item, val);
						valToType.put(item, val.getClass());
						if (last != null && Reflect.isWrapper(last.getClass())
						 ? last.equals(item) : last == item) {
							count++;
						} else {
							appendValue(text, last);
							if (count > 1) text.append(" ▶×").append(count).append("◀");
							text.append(getArrayDelimiter());
							if (isTruncate(text.length())) break;
							last = item;
							count = 0;
						}
					}
					appendValue(text, last);
					if (count > 1) text.append(" ▶×").append(count).append("◀");
					text.append(", ");
					// break l;
					checkTail = true;
				}
			} catch (ArcRuntimeException ignored) {
				break iter;
			} catch (Throwable e) {
				if (DEBUG) Log.err(e);
				text.append("▶ERROR◀");
			}
			if (checkTail && text.length() >= 2) text.delete(text.length() - 2, text.length());
			text.append(']');
			setColor(Color.white);
			return;
		}

		startIndexMap.put(text.length(), val);
		text.append(toString(val));
		endIndexMap.put(val, text.length());

		Color mainColor = val == null ? Syntax.c_objects
		 : type == String.class || val instanceof Character ? Syntax.c_string
		 : Number.class.isAssignableFrom(box(type)) ? Syntax.c_number
		 : val.getClass().isEnum() ? c_enum
		 : box(val.getClass()) == Boolean.class ? Syntax.c_objects
		 : Color.white;

		setColor(mainColor);
	}
	private static String toString(Object val) {
		return CatchSR.<String>apply(() ->
		 CatchSR.of(() ->
			 val instanceof String ? '"' + (String) val + '"'
				: val instanceof Character ? STR."'\{val}'" /* + (int) (Character) val */
				: val instanceof Float ? FormatHelper.fixed((float) val, 2)
				: val instanceof Double ? FormatHelper.fixed((double) val, 2)

				: val instanceof Element ? ElementUtils.getElementName((Element) val)
				: FormatHelper.getUIKey(val))
			.get(() -> String.valueOf(val))
			.get(() -> Tools.clName(val) + "@" + Integer.toHexString(val.hashCode()))
			.get(() -> Tools.clName(val))
		);
	}

	private void appendMap(StringBuilder sb, Object key, Object value) {
		appendValue(sb, key);
		sb.append('=');
		appendValue(sb, value);
		sb.append(getArrayDelimiter());
	}
	private static String getArrayDelimiter() {
		return E_JSFunc.array_delimiter.getString();
	}

	private boolean isTruncate(int length) {
		return enableTruncate && truncate_text.enabled() && length > truncateLength;
	}
	private CharSequence truncate(CharSequence text) {
		//noinspection StringTemplateMigration
		return isTruncate(text.length()) ? text.subSequence(0, truncateLength) + "  ..." : text;
	}

	public int getCursor(float x, float y) {
		var   runs       = cache.getLayouts().first().runs;
		float lineHeight = style.font.getLineHeight();
		float
		 currentX,
		 currentY = height; // 指文字左上角的坐标
		boolean lineValid  = Math.abs(currentY - y) < lineHeight;
		int     accumulate = 0;
		for (GlyphRun run : runs) {
			FloatSeq xAdvances = run.xAdvances;
			// 第一个条目是相对于绘图位置的 X 偏移量
			currentX = 0;
			if (lineValid) {
				for (int i = 0; i < xAdvances.size; i++) {
					currentX += xAdvances.get(i);

					if (currentX >= x) {
						return accumulate;
					}
					accumulate++;
				}
			} else {
				accumulate += xAdvances.size;
			}
			// 新的一行
			currentY -= lineHeight;
			// 判断是否在行
			lineValid = Math.abs(currentY - y) < lineHeight;
		}
		return -1;
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
			setValInternal(valueFunc.get(newVal));
		} catch (Throwable th) {
			Log.err(th);
			setValInternal(newVal);
		}
	}
	private void setValInternal(Object val) {
		if (this.val == val && (type.isPrimitive() || Reflect.isWrapper(type) || type == String.class)) return;
		if (this.val != null && val != null &&
		    this.val.getClass() == Vec2.class && val.getClass() == Vec2.class &&
		    this.val.equals(val)) return;

		this.val = val;
		if (afterSet != null) afterSet.run();
		try {
			setAndProcessText(val);
		} catch (Throwable th) {
			resolveThrow(th);
			super.setText(String.valueOf(val));
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
					 CatchSR.of(() -> FormatHelper.getUIKey(fieldVal))
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
				 s -> FormatHelper.fieldFormat(ShowUIList.styleKeyMap.get(s)),
				 Float.NEGATIVE_INFINITY, 32,
				 true, Align.top);
			}));
		}

		list.add(MenuItem.with("clear", Icon.eraserSmall, "@clear", this::clearVal));
		/* list.add(MenuItem.with("truncate", Icon.listSmall, () -> STR."\{enableTruncate ? "Disable" : "Enable"} Truncate", () -> {
			enableTruncate = !enableTruncate;
		})); */

		list.add(enabledUpdateMenu() ?
		 CheckboxList.withc("autoRefresh", Icon.refresh1Small, "Auto Refresh", () -> enableUpdate, () -> {
			 enableUpdate = !enableUpdate;
		 }) : null);

		list.add(MenuBuilder.copyAsJSMenu("value", () -> val));
		return list;
	}

	protected void detailsBuild(Seq<MenuItem> list) {
		list.add(newDetailsMenuList(this, () -> val, type));
	}
	protected void specialBuild(Seq<MenuItem> list) {
		SR.apply(() -> SR.of(type)
		 .isExtend(cl -> {
			 if (cl == Drawable.class) addPickDrawable(list);
			 list.add(MenuItem.with("img.show", Icon.imageSmall, "img", () ->
				SR.apply(() -> SR.of(val)
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
			 elementSetter(list, this::setValInternal);
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
		 }, Building.class, Unit.class, Bullet.class)
		);
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


	private static class ItemValueLabel extends ValueLabel {
		public static final ItemValueLabel THE_ONE = new ItemValueLabel();

		private Object  obj;
		private Prov<?> prov;
		private ItemValueLabel() {
			super();
		}
		public static ItemValueLabel of(Object obj, Class<?> valType, Prov<?> val) {
			ItemValueLabel label = THE_ONE;
			label.type = valType;
			label.obj = obj;
			label.prov = val;
			label.clearListeners();
			label.addFocusListener();
			label.flushVal();
			return label;
		}
		public Seq<MenuItem> getMenuLists() {
			Seq<MenuItem> list = new Seq<>();
			basicMenuLists(list);
			list.insert(0, InfoList.withi("obj", Icon.infoSmall, () -> ValueLabel.toString(val)));
			return list;
		}
		public boolean enabledUpdateMenu() {
			return false;
		}
		public void setNewVal(Object newVal) {
			// do nothing.
		}
		public void flushVal() {
			val = prov.get();
		}
		public Object getObject() {
			return obj;
		}
	}
}