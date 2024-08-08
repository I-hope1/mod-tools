package modtools.ui.comp.utils;

import arc.Core;
import arc.files.Fi;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.TextureRegion;
import arc.input.KeyCode;
import arc.math.geom.*;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.layout.Cell;
import arc.struct.*;
import arc.util.*;
import arc.util.pooling.*;
import arc.util.pooling.Pool.Poolable;
import mindustry.Vars;
import mindustry.entities.Effect;
import mindustry.gen.*;
import mindustry.world.Tile;
import modtools.content.ui.*;
import modtools.content.world.Selection;
import modtools.events.*;
import modtools.jsfunc.*;
import modtools.ui.*;
import modtools.ui.comp.input.InlineLabel;
import modtools.ui.comp.input.highlight.Syntax;
import modtools.ui.comp.review.*;
import modtools.ui.gen.HopeIcons;
import modtools.ui.menu.*;
import modtools.utils.*;
import modtools.utils.ArrayUtils.AllCons;
import modtools.utils.JSFunc.JColor;
import modtools.utils.SR.SatisfyException;
import modtools.utils.reflect.*;
import modtools.utils.ui.*;

import java.util.Map;

import static modtools.events.E_JSFunc.*;
import static modtools.jsfunc.type.CAST.box;
import static modtools.ui.Contents.selection;
import static modtools.ui.IntUI.topGroup;
import static modtools.ui.comp.input.highlight.Syntax.c_map;

@SuppressWarnings({"SizeReplaceableByIsEmpty"})
public abstract class ValueLabel extends InlineLabel {
	public static final boolean DEBUG     = false;
	public static final Object  unset     = new Object();
	public static final Color   c_enum    = new Color(0xFFC66D_FF);
	public static final String  NULL_MARK = "`*null";

	public String ellipse = "  ...";


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

		layout.ignoreMarkup = true;

		MyEvents.on(E_JSFuncDisplay.value, () -> shown = E_JSFuncDisplay.value.enabled());

		addFocusListener();

		Object[] val = {null};
		addListener(new InputListener() {
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				int    cursor    = getCursor(x, y);
				Object bestSoFar = null;
				Object o;
				int    toIndex;
				for (int i = 0; i <= cursor; i++) {
					if (!startIndexMap.containsKey(i)) continue;
					o = startIndexMap.get(i);
					toIndex = endIndexMap.get(o);
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
				if (type1 != null && obj != null) {
					label = ItemValueLabel.of(obj, type1, () -> val[0]);
				}
			}
			return label.getMenuLists();
		});
	}
	public void addFocusListener() {
		if (type == null) {
		} else if (Element.class.isAssignableFrom(type) || val instanceof Element) {
			ReviewElement.addFocusSource(this, () -> ElementUtils.findWindow(this),
			 () -> val instanceof Element ? (Element) val : null);
		} else if (Cell.class.isAssignableFrom(type) || val instanceof Cell<?>) {
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

	public void setAndProcessText(Object val) {
		text.setLength(0);
		colorMap.clear();
		startIndexMap.clear();
		endIndexMap.clear();
		appendValue(text, val);
		if (text.length() > truncate_length.getInt()) {
			text.setLength(truncate_length.getInt());
			text.append(ellipse);
		}
	}

	private final IntMap<Object>              startIndexMap = new IntMap<>();
	private final ObjectIntMap<Object>        endIndexMap   = new ObjectIntMap<>();
	private final ObjectMap<Object, Class<?>> valToType     = new ObjectMap<>();
	private final ObjectMap<Object, Object>   valToObj      = new ObjectMap<>();
	// 用于记录数组或map是否展开
	private final ObjectMap<Object, Boolean>  expandMap     = new ObjectMap<>();

	@SuppressWarnings("ConstantConditions")
	private void appendValue(StringBuilder text, Object val) {
		if (val instanceof ObjectMap || val instanceof IntMap<?>
		    || val instanceof ObjectFloatMap<?> || val instanceof Map) {
			if (!expandMap.containsKey(val)) {
				clickedRegion(() -> {
					int start = startIndexMap.findKey(val, true, Integer.MAX_VALUE);
					int end   = endIndexMap.get(val);
					return Tmp.p1.set(start, end);
				}, () -> toggleExpand(val));
				expandMap.put(val, false);
			}
			startIndexMap.put(text.length(), val);
			colorMap.put(text.length(), c_map);
			text.append("|Map ").append(getSize(val)).append('|');
			colorMap.put(text.length(), Color.white);
			endIndexMap.put(val, text.length());

			if (!expandMap.get(val, false)) {
				return;
			}
			text.append('\n');
			text.append('{');
			Runnable prev = appendTail;
			appendTail = null;
			switch (val) {
				case ObjectMap<?, ?> map -> {
					for (ObjectMap.Entry<?, ?> entry : map) {
						appendMap(text, val, entry.key, entry.value);
						if (isTruncate(text.length())) break;
					}
				}
				case IntMap<?> map -> {
					for (IntMap.Entry<?> entry : map) {
						appendMap(text, val, entry.key, entry.value);
						if (isTruncate(text.length())) break;
					}
				}
				case ObjectFloatMap<?> map -> {
					for (ObjectFloatMap.Entry<?> entry : map) {
						appendMap(text, val, entry.key, entry.value);
						if (isTruncate(text.length())) break;
					}
				}
				default -> {
					for (Map.Entry<?, ?> entry : ((Map<?, ?>) val).entrySet()) {
						appendMap(text, val, entry.getKey(), entry.getValue());
						if (isTruncate(text.length())) break;
					}
				}
			}
			appendTail = prev;
			text.append('}');

			return;
		}
		iter:
		if ((val instanceof Iterable || (val != null && val.getClass().isArray()))) {
			text.append('[');

			Pool<IterCons> pool = Pools.get(IterCons.class, IterCons::new, 50);
			IterCons       cons = pool.obtain().init(this, val, text);
			try {
				try {
					Runnable prev = appendTail;
					appendTail = null;
					if (val instanceof Iterable<?> iter) {
						for (Object item : iter) {
							cons.get(item);
						}
					} else {
						ArrayUtils.forEach(val, cons);
					}
					appendTail = prev;
				} catch (SatisfyException ignored) { }
			} catch (ArcRuntimeException ignored) {
				break iter;
			} catch (Throwable e) {
				if (DEBUG) Log.err(e);
				text.append("▶ERROR◀");
			} finally {
				pool.free(cons);
			}
			text.append(']');
			// setColor(Color.white);
			return;
		}

		int textOff = 0;
		if (text.length() > 0 && val instanceof Color c) {
			int i = text.length();
			colorMap.put(i, c);
			text.append('■');
			textOff = text.length() - i;
			// colorMap.put(text.length(), Color.white);
		}

		Color mainColor = colorOf(val);
		int   startI    = text.length();
		colorMap.put(startI, mainColor);
		boolean b = testHashCode(wrapVal(val));
		if (b) startIndexMap.put(startI - textOff, wrapVal(val));
		text.append(toString(val));
		int endI = text.length();
		if (b) endIndexMap.put(wrapVal(val), endI);
		colorMap.put(endI, Color.white);
	}
	private void toggleExpand(Object val) {
		expandMap.put(val, !expandMap.get(val, false));
		// Core.app.post(() -> flushVal());
	}

	private int getSize(Object val) {
		return switch (val) {
			case ObjectMap<?, ?> map -> map.size;
			case IntMap<?> map -> map.size;
			case ObjectFloatMap<?> map -> map.size;
			default -> ((Map<?, ?>) val).size();
		};
	}
	public static boolean testHashCode(Object object) {
		try {
			object.hashCode();
			return true;
		} catch (Throwable e) {
			return false;
		}
	}

	// 一些基本类型的特化，不装箱，为了减少内存消耗

	// 对于整数类型的处理 (包括 byte 和 short)
	private void appendValue(StringBuilder text, int val) {
		colorMap.put(text.length(), Syntax.c_number);
		text.append(val);
		colorMap.put(text.length(), Color.white);
	}

	// 对于长整数类型的处理
	private void appendValue(StringBuilder text, long val) {
		colorMap.put(text.length(), Syntax.c_number);
		text.append(val);
		colorMap.put(text.length(), Color.white);
	}

	// 对于浮点数类型的处理 (包括 float 和 double)
	private void appendValue(StringBuilder text, float val) {
		colorMap.put(text.length(), Syntax.c_number);
		text.append(FormatHelper.fixed(val, 2));
		colorMap.put(text.length(), Color.white);
	}

	// 对于字符类型的处理
	private void appendValue(StringBuilder text, char val) {
		colorMap.put(text.length(), Syntax.c_string);
		text.append('\'').append(val).append('\'');
		colorMap.put(text.length(), Color.white);
	}

	private void addCountText(StringBuilder text, int count) {
		if (count > 1) {
			colorMap.put(text.length(), Color.gray);
			text.append(" ×").append(count);
			colorMap.put(text.length(), Color.white);
		}
	}
	private Object wrapVal(Object val) {
		if (val == null) return NULL_MARK;
		return val;
	}
	public static Color colorOf(Object val) {
		return val == null ? Syntax.c_objects
		 : val instanceof String || val instanceof Character ? Syntax.c_string
		 : val instanceof Number ? Syntax.c_number
		 : val instanceof Class ? TmpVars.c1.set(JColor.c_type)
		 : val.getClass().isEnum() ? c_enum
		 : box(val.getClass()) == Boolean.class ? Syntax.c_keyword
		 : Color.white;
	}
	private static String toString(Object val) {
		return CatchSR.apply(() ->
		 CatchSR.of(() ->
			 val instanceof String ? '"' + (String) val + '"'
				: val instanceof Character ? STR."'\{val}'"
				: val instanceof Float || val instanceof Double ? FormatHelper.fixed(((Number) val).floatValue(), 2)
			  : val instanceof Class ? ((Class<?>) val).getSimpleName()

				: val instanceof Element ? ReviewElement.getElementName((Element) val)
				: FormatHelper.getUIKey(val))
			.get(() -> String.valueOf(val))
			.get(() -> Tools.clName(val) + "@" + Integer.toHexString(val.hashCode()))
			.get(() -> Tools.clName(val))
		);
	}

	private void appendMap(StringBuilder sb, Object mapObj,
	                       int key,
	                       Object value) {
		valToObj.put(value, mapObj);
		valToType.put(value, value == null ? Object.class : value.getClass());
		postAppendDelimiter(sb);
		appendValue(sb, key);
		sb.append('=');
		appendValue(sb, value);
	}
	private void appendMap(StringBuilder sb, Object mapObj,
	                       Object key,
	                       Object value) {
		valToObj.put(key, mapObj);
		valToObj.put(value, mapObj);
		valToType.put(key, key.getClass());
		valToType.put(value, value == null ? Object.class : value.getClass());
		postAppendDelimiter(sb);
		appendValue(sb, key);
		sb.append('=');
		appendValue(sb, value);
	}
	private Runnable appendTail;
	private void postAppendDelimiter(StringBuilder sb) {
		if (appendTail != null) appendTail.run();
		appendTail = () -> sb.append(getArrayDelimiter());
	}
	private static String getArrayDelimiter() {
		return E_JSFunc.array_delimiter.getString();
	}

	private boolean isTruncate(int length) {
		return enableTruncate && truncate_text.enabled() && length > truncate_length.getInt();
	}
	public void clearVal() {
		val = "";
		super.setText((CharSequence) null);
		prefSizeInvalid = true;
	}
	public static final String ERROR     = "<ERROR>";
	public static final String STR_EMPTY = "<EMPTY>";
	public void setError() {
		super.setText(ERROR);
		setColor(Color.red);
	}
	public void setText(CharSequence newText) {
		throw new UnsupportedOperationException("the ValueLabel cannot be set by setText(newText)");
	}
	void setText0(CharSequence newText) {
		if (newText == null || newText.length() == 0) {
			newText = STR_EMPTY;
			setColor(Color.gray);
		} else setColor(Color.white);
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
		if (this.val == val) {
			if (type.isPrimitive() || Reflect.isWrapper(type) || type == String.class
			    || type == Class.class || (val != null && val.getClass() == Object.class)) {
				return;
			}
		}

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

		if (Style.class.isAssignableFrom(type) || val instanceof Style) {
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
				 ArrayUtils.seq(ShowUIList.styleKeyMap.keySet())
					.retainAll(type::isInstance),
				 () -> (Style) val, this::setNewVal,
				 s -> FormatHelper.fieldFormat(ShowUIList.styleKeyMap.get(s)),
				 Float.NEGATIVE_INFINITY, 32,
				 true, Align.top);
			}));
		}
		if (Fi.class.isAssignableFrom(type) || val instanceof Fi) {
			list.add(DisabledList.withd("fi.open", Icon.fileSmall, "Open", () -> val == null, () -> Core.app.openFolder(((Fi) val).path())));
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
		 }, Tile.class, Building.class, Unit.class, Bullet.class, Posc.class)
		);
	}
	private void addPickDrawable(Seq<MenuItem> list) {
		list.add(MenuItem.with("drawable.pick", Icon.editSmall, "@pickdrawable", () -> {
			if (val instanceof Drawable d)
				IntUI.drawablePicker().show(d, true, this::setNewVal);
		}));
	}

	protected void elementSetter(Seq<MenuItem> list, Cons<Element> callback) {
		elementSetter(list, Element.class, callback);
	}

	protected <T extends Element> void elementSetter(Seq<MenuItem> list, Class<T> elementType, Cons<T> callback) {
		list.add(DisabledList.withd("element.pick", Icon.editSmall, "Select and Replace",
		 topGroup::isSelecting,
		 () -> topGroup.requestSelectElem(TopGroup.defaultDrawer, elementType, callback)
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
			list.insert(0, InfoList.withi("obj", Icon.infoSmall, () -> ValueLabel.toString(val))
			 .color(colorOf(val)));
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
	public static final ObjectSet<Class<?>> identityClasses = ObjectSet.with(
	 Vec2.class, Rect.class, Color.class
	);
	private static class IterCons extends AllCons implements Poolable {
		private Object        val;
		private StringBuilder text;

		private int        count;
		private boolean    gotFirst;
		private ValueLabel self;
		public IterCons init(ValueLabel self, Object val, StringBuilder text) {
			this.self = self;
			this.val = val;
			this.text = text;
			return this;
		}
		private Object last;
		public void get(Object item) {
			if (!gotFirst) {
				gotFirst = true;
				last = item;
			}
			self.valToObj.put(item, val);
			self.valToType.put(item, val.getClass());
			if (last != null && identityClasses.contains(val.getClass()) ? !last.equals(item) : last != item) {
				self.postAppendDelimiter(text);
				self.appendValue(text, last);
				self.addCountText(text, count);
				if (self.isTruncate(text.length())) throw new SatisfyException();
				last = item;
				count = 0;
			} else {
				count++;
			}
		}
		private int ilast;
		public void get(int item) {
			if (!gotFirst) {
				gotFirst = true;
				ilast = item;
			}
			if (item != ilast) {
				self.postAppendDelimiter(text);
				self.appendValue(text, ilast);
				self.addCountText(text, count);
				if (self.isTruncate(text.length())) throw new SatisfyException();
				ilast = item;
				count = 0;
			} else {
				count++;
			}
		}
		private float flast;
		public void get(float item) {
			if (!gotFirst) {
				gotFirst = true;
				flast = item;
			}
			if (item != flast) {
				self.postAppendDelimiter(text);
				self.appendValue(text, flast);
				self.addCountText(text, count);
				if (self.isTruncate(text.length())) throw new SatisfyException();
				flast = item;
				count = 0;
			} else {
				count++;
			}
		}
		private double dlast;
		public void get(double item) {
			if (!gotFirst) {
				gotFirst = true;
				dlast = item;
			}
			if (item != dlast) {
				self.postAppendDelimiter(text);
				self.appendValue(text, (float) dlast);
				self.addCountText(text, count);
				if (self.isTruncate(text.length())) throw new SatisfyException();
				dlast = item;
				count = 0;
			} else {
				count++;
			}
		}
		private long llast;
		public void get(long item) {
			if (!gotFirst) {
				gotFirst = true;
				llast = item;
			}
			if (item != llast) {
				self.postAppendDelimiter(text);
				self.appendValue(text, llast);
				self.addCountText(text, count);
				if (self.isTruncate(text.length())) throw new SatisfyException();
				llast = item;
				count = 0;
			} else {
				count++;
			}
		}
		private boolean zlast;
		public void get(boolean item) {
			if (!gotFirst) {
				gotFirst = true;
				zlast = item;
			}
			if (item != zlast) {
				self.postAppendDelimiter(text);
				self.appendValue(text, zlast);
				self.addCountText(text, count);
				if (self.isTruncate(text.length())) throw new SatisfyException();
				zlast = item;
				count = 0;
			} else {
				count++;
			}
		}
		private char clast;
		public void get(char item) {
			if (!gotFirst) {
				gotFirst = true;
				clast = item;
			}
			if (item != clast) {
				self.postAppendDelimiter(text);
				self.appendValue(text, clast);
				self.addCountText(text, count);
				if (self.isTruncate(text.length())) throw new SatisfyException();
				clast = item;
				count = 0;
			} else {
				count++;
			}
		}


		public void reset() {
			last = null;
			self = null;
			count = 0;
			gotFirst = false;
		}
	}
}