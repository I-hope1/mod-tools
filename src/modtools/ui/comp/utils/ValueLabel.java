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
import arc.struct.IntMap.Keys;
import arc.util.*;
import mindustry.Vars;
import mindustry.entities.Effect;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.world.Tile;
import modtools.content.ui.*;
import modtools.content.world.Selection;
import modtools.events.*;
import modtools.jsfunc.*;
import modtools.ui.*;
import modtools.ui.comp.input.ExtendingLabel;
import modtools.ui.comp.input.highlight.Syntax;
import modtools.ui.comp.review.*;
import modtools.ui.comp.utils.Viewers.ViewerItem;
import modtools.ui.gen.HopeIcons;
import modtools.ui.menu.*;
import modtools.utils.*;
import modtools.utils.io.FileUtils;
import modtools.utils.reflect.*;
import modtools.utils.ui.FormatHelper;

import static modtools.events.E_JSFunc.*;
import static modtools.jsfunc.type.CAST.box;
import static modtools.ui.Contents.selection;
import static modtools.ui.IntUI.topGroup;

public abstract class ValueLabel extends ExtendingLabel {
	//region Static Fields & Constants
	public static final boolean DEBUG     = false;
	/** 这个要和null判断一起 */
	public static final Object  unset     = new Object();
	public static final Color   c_enum    = new Color(0xFFC66D_FF);
	public static final String  NULL_MARK = "`*null";
	public static final String ERROR     = "<ERROR>";
	public static final String STR_EMPTY = "<EMPTY>";

	private static       ValueLabel hoveredLabel;
	private static       Object     hoveredVal;
	private static final Point2     hoveredChunk = new Point2();

	/** configuration */
	public static final int STEP_SIZE = 64;
	public static Color[] bgColors = new Color[]{
	 new Color(0xFFC66D_66),
	 new Color(0xFFC6FF_66),
	 new Color(0xFC66C6_66),
	 new Color(0x66FFC6_66),
	 };
	//endregion

	//region Instance Fields
	public               Object     val;
	public               Class<?>   type;
	public int maxItemCount   = STEP_SIZE;
	/** 是否启用截断文本（当文本过长时，容易内存占用过大） */
	public boolean
							 enableTruncate = true,
	/** 是否启用更新 */
	enableUpdate = true;

	public Func<Object, Object> valueFunc = o -> o;

	public final IntMap<Object>              startIndexMap = new IntMap<>();
	public final ObjectIntMap<Object>        endIndexMap   = new ObjectIntMap<>();
	// 用于记录数组或map的类型
	public final ObjectMap<Object, Class<?>> valToType     = new ObjectMap<>();
	// 用于记录数组或map的值
	public final ObjectMap<Object, Object>   valToObj      = new ObjectMap<>();
	// 用于记录数组或map是否展开
	public final ObjectMap<Object, Boolean>  expandVal     = new ObjectMap<>();

	private       int     bgIndex;
	Runnable appendTail;
	boolean shown = E_JSFuncDisplay.value.enabled();
	boolean cleared;
	public Runnable afterSet;
	//endregion

	//region Hover State Accessors
	public Object hoveredVal() {
		return hoveredLabel == this ? hoveredVal : null;
	}
	public Point2 hoveredChunk() {
		return hoveredLabel == this ? hoveredChunk : UNSET_P;
	}
	//endregion

	//region Constructors & Initialization
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

		Core.app.post(() -> addFocusListener());

		addListener(new InputListener() {
			public boolean mouseMoved(InputEvent event, float x, float y) {
				hover(x, y);
				return true;
			}
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				hover(x, y);
				return super.touchDown(event, x, y, pointer, button);
			}
			public void exit(InputEvent event, float x, float y, int pointer, Element toActor) {
				super.exit(event, x, y, pointer, toActor);
				hoveredLabel = null;
			}
			private final IntSeq keys = new IntSeq();
			private void hover(float x, float y) {
				hoveredVal = null;
				hoveredLabel = null;
				hoveredChunk.set(UNSET_P);

				int    cursor = getCursor(x, y);
				Object o;
				int    toIndex;

				Keys keys1 = startIndexMap.keys();
				keys.clear();
				while (keys1.hasNext) keys.add(keys1.next());
				keys.sort();

				for (int i = 0, size = keys.size; i < size; i++) {
					int index = keys.get(i);
					o = startIndexMap.get(index);
					toIndex = endIndexMap.get(o);
					if (index <= cursor && cursor < toIndex) {
						hoveredChunk.set(index, toIndex);
						hoveredLabel = ValueLabel.this;
						hoveredVal = o;
						return;
					}
				}
			}
		});
		MenuBuilder.addShowMenuListenerp(this, () -> {
			ValueLabel label = this;
			// Log.info(hoveredVal);
			final Object val = hoveredVal;
			if (val != null) {
				Class<?> type1 = valToType.get(val);
				Object   obj   = valToObj.get(val);
				if (type1 != null && obj != null) {
					label = ItemValueLabel.of(obj, type1, () -> val);
				}
			}
			return label.getMenuLists();
		});
	}

	public void addFocusListener() {
		if (type == null) {
			// Do nothing if type is null
		} else if (Element.class.isAssignableFrom(type) || val instanceof Element) {
			ReviewElement.addFocusSource(this, () -> ElementUtils.findWindow(this),
			 () -> val instanceof Element ? (Element) val : null);
		} else if (Cell.class.isAssignableFrom(type) || val instanceof Cell<?>) {
			ReviewElement.addFocusSource(this, () -> ElementUtils.findWindow(this),
			 () -> val instanceof Cell<?> cell ? cell.get() : null);
		}

		Selection.addFocusSource(this, () -> hoveredVal());
	}
	//endregion

	//region Abstract Methods
	public abstract Seq<MenuItem> getMenuLists();
	public abstract void flushVal();
	public abstract Object getObject();
	//endregion

	//region Core Value Handling
	/** <b>PS:</b> 这可能会设置字段值 */
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
		if (HopeReflect.isSameVal(val, this.val, type)) return;

		if (val != null && val != unset && !box(type).isInstance(val)) {
			throw new IllegalArgumentException("val must be a " + type.getName());
		}

		this.val = val;
		try {
			setAndProcessText(val);
		} catch (Throwable th) {
			resolveThrow(th);
			super.setText(String.valueOf(val));
		} finally {
			if (afterSet != null) afterSet.run();
		}
		invalidateHierarchy();
	}

	public void setAndProcessText(Object val) {
		text.setLength(0);
		colorMap.clear();
		startIndexMap.clear();
		endIndexMap.clear();
		clearDrawRuns();
		bgIndex = 0;
		appendValue(val);

		if (hover_outline.enabled() && !hoveredChunk().equals(UNSET_P)) addDrawRun(hoveredChunk().x, hoveredChunk().y, DrawType.outline, Pal.accent);

		if (isTruncate()) {
			text.setLength(truncate_length.getInt());
			text.append(ellipsis);
		}
	}

	public void clearVal() {
		val = null;
		super.setText((CharSequence) null);
		prefSizeInvalid = true;
	}

	public void setError() {
		super.setText(ERROR);
		setColor(Color.red);
	}

	public void setText(CharSequence newText) {
		throw new UnsupportedOperationException("the ValueLabel cannot be set by setText(newText)");
	}
	void setText0(CharSequence newText) {
		if (newText == null || newText.length() == 0) {
			colorMap.clear();
			newText = STR_EMPTY;
			setColor(Color.gray);
		} else { setColor(Color.white); }
		super.setText(newText);
	}
	//endregion

	//region Text Appending & Formatting
	public Color bgColor() {
		return colorful_background.enabled() ? bgColors[bgIndex++ % bgColors.length] : bgColors[0];
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private boolean applyViewer(
	 Seq<ViewerItem<?>> viewers, Object val) {
		for (ViewerItem item : viewers) {
			if (item.valid(val) && item.view(val, this)) return true;
		}
		return false;
	}

	public void appendText(String text) {
		this.text.append(text);
	}
	@SuppressWarnings("ConstantConditions")
	public void appendValue(Object val) {
		int valStart = text.length();

		// viewers
		if (val != null) {
			try {
				if (valStart == text.length() &&
				    applyViewer(Viewers.customViewers, val)) { return; }
				if (valStart == text.length() &&
				    applyViewer(Viewers.internalViewers, val)) { return; }
			} catch (Throwable e) {
				Log.err("Failed to apply viewer for " + val, e);
				appendError(this, text);
			}
		}
		Viewers.defaultAppend(this, valStart, val);
	}

	// 一些基本类型的特化，不装箱，为了减少内存消耗

	// 对于整数类型的处理 (包括 byte 和 short)
	public void appendValue(int val) {
		startColor(Syntax.c_number);
		text.append(val);
		endColor();
	}

	// 对于长整数类型的处理
	public void appendValue(long val) {
		startColor(Syntax.c_number);
		text.append(val);
		endColor();
	}

	// 对于浮点数类型的处理 (包括 float 和 double)
	public void appendValue(float val) {
		startColor(Syntax.c_number);
		text.append(FormatHelper.fixed(val, 2));
		endColor();
	}

	// 对于字符类型的处理
	public void appendValue(char val) {
		startColor(Syntax.c_string);
		text.append('\'').append(val).append('\'');
		endColor();
	}

	public void addCountText(int count) {
		if (count > 1) {
			startColor(Color.gray);
			text.append(" ×").append(count);
			endColor();
		}
	}

	public void appendMap(Object mapObj,
	                      int key,
	                      Object value) {
		valToObj.put(value, mapObj);
		valToType.put(value, value == null ? Object.class : value.getClass());
		postAppendDelimiter();
		appendValue(key);
		text.append('=');
		appendValue(value);
	}
	public void appendMap(Object mapObj,
	                      Object key,
	                      Object value) {
		if (key != null) {
			valToObj.put(key, mapObj);
			valToType.put(key, key.getClass());
		}
		if (value != null) {
			valToObj.put(value, mapObj);
			valToType.put(value, value.getClass());
		}
		postAppendDelimiter();
		appendValue(key);
		text.append('=');
		appendValue(value);
	}

	public void postAppendDelimiter() {
		if (appendTail != null) appendTail.run();
		appendTail = () -> text.append(Viewers.getArrayDelimiter());
	}

	boolean isTruncate(int length) {
		return enableTruncate && R_JSFunc.truncate_text && length > R_JSFunc.truncate_length;
	}
	boolean isTruncate() {
		return isTruncate(text.length());
	}

	public int startColor(Color color) {
		int i = text.length();
		colorMap.put(i, color);
		return i;
	}
	public void endColor() {
		colorMap.put(text.length(), Color.white);
	}
	public static void appendError(ValueLabel label, StringBuilder text) {
		label.startColor(Color.red);
		text.append(ERROR);
		label.endColor();
	}
	//endregion

	//region Menu Building
	public static MenuItem newElementDetailsList(Element element) {
		return DisabledList.withd("elem.details", Icon.craftingSmall, "Elem Details", () -> element == null,
		 () -> new ElementDetailsWindow(element));
	}
	public static <T> MenuItem newDetailsMenuList(Element el, Prov<T> val, Class<?> type) {
		return DisabledList.withd("details", Icon.infoCircleSmall, "@details",
		 () -> type.isPrimitive() && val.get() == null,
		 () -> showNewInfo(el, val.get(), type));
	}

	/** 如果val == unset，则不添加任何特殊菜单 */
	protected Seq<MenuItem> basicMenuLists(Seq<MenuItem> list) {
		// 如果val == unset，则不添加任何菜单
		if (val == unset) return list;
		specialBuild(list);
		if (list.any()) list.add(UnderlineItem.with());
		detailsBuild(list);
		list.add(MenuItem.with("clear", Icon.eraserSmall, "@clear", this::clearVal));
		/* list.add(MenuItem.with("truncate", Icon.listSmall, () -> STR."\{enableTruncate ? "Disable" : "Enable"} Truncate", () -> {
			enableTruncate = !enableTruncate;
		})); */

		list.add(enabledUpdateMenu() ?
		 CheckboxList.withc("autoRefresh", Icon.refresh1Small, "Auto Refresh", () -> enableUpdate, () -> {
			 enableUpdate = !enableUpdate;
		 }) : null);

		list.add(MenuBuilder.copyAsJSMenu("value", () -> val));
		list.add(UnderlineItem.with());
		list.add(MenuItem.with("change.class", Icon.pencilSmall, "Change Class", () -> {
			new ChangeClassDialog(this).show();
		}));
		// list.add(MenuItem.with("viewer.set", Icon.eyeSmall, "Set Viewer",  () -> { }));
		list.add(UnderlineItem.with());
		if (String.class.isAssignableFrom(type) || val instanceof String) {
			list.add(DisabledList.withd("string.copy", Icon.copySmall, "Copy", this::valueIsNull, () -> {
				JSFunc.copyText((String) val);
			}));
		}
		if (Style.class.isAssignableFrom(type) || val instanceof Style) {
			list.add(DisabledList.withd("style.copy", Icon.copySmall, "Copy Style", this::valueIsNull, () -> {
				copyStyle(val);
			}));
			list.add(DisabledList.withd("style.set", Icon.copySmall, "Set Style", this::valueIsNull, () -> {
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
			list.add(DisabledList.withd("fi.open", Icon.fileSmall, "Open", this::valueIsNull, () -> FileUtils.openFile((Fi) val)));
		}
		return list;
	}
	public static void copyStyle(Object val1) {
		Class<?>      cls     = val1.getClass();
		StringBuilder builder = new StringBuilder(STR."new \{ClassUtils.getSuperExceptAnonymous(cls).getSimpleName()}(){{\n");
		ClassUtils.walkPublicNotStaticKeys(cls, field -> {
			Object fieldVal = FieldUtils.getOrNull(field, val1);
			if (fieldVal == null || (fieldVal instanceof Number n && n.intValue() == 0)) return;
			String uiKey = CatchSR.apply(() ->
			 CatchSR.of(() -> FormatHelper.getUIKey(fieldVal))
				.get(() -> String.valueOf(fieldVal))
			);
			builder.append(STR."\t\{field.getName()} = \{uiKey};\n");
		});
		builder.append("}}");
		JSFunc.copyText(builder);
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
				 .isInstance(Drawable.class, INFO_DIALOG::dialogd)
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
				this::valueIsNull, () -> {
					if (!selection.focusInternal.add(val)) selection.focusInternal.remove(val);
				}));
		 }, Tile.class, Building.class, Unit.class, Bullet.class, Posc.class)
		);
	}
	public boolean valueIsNull() {
		return val == null || val == unset;
	}
	private void addPickDrawable(Seq<MenuItem> list) {
		list.add(MenuItem.with("drawable.pick", Icon.editSmall, "@pickdrawable", () -> {
			if (val instanceof Drawable d) { IntUI.drawablePicker().show(d, true, this::setNewVal); }
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

	private static void showNewInfo(Element el, Object val1, Class<?> type) {
		Vec2 pos = ElementUtils.getAbsolutePos(el);
		try {
			INFO_DIALOG.showInfo(val1, val1 != null ? val1.getClass() : type).setPosition(pos);
		} catch (Throwable e) {
			IntUI.showException(e).setPosition(pos);
		}
	}
	//endregion

	//region Utility & Overrides
	public long getOffset() {
		throw new UnsupportedOperationException("Not implemented yet");
	}

	public Prov<Point2> getPoint2Prov(Object val) {
		return () -> {
			int start = startIndexMap.findKey(val, true, Integer.MAX_VALUE);
			int end   = endIndexMap.get(val);
			return Tmp.p1.set(start, end);
		};
	}
	public void toggleExpand(Object val) {
		expandVal.put(val, !expandVal.get(val, false));
		Core.app.post(this::flushVal);
	}

	public boolean enabledUpdateMenu() {
		return true;
	}

	public float getWidth() {
		return shown ? super.getWidth() : 0;
	}
	public void draw() {
		if (shown) super.draw();
	}

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
	//endregion

	//region Private Helper Methods
	private void resolveThrow(Throwable th) {
		Log.err(th);
		IntUI.showException(th);
	}
	//endregion

	//region Inner Class: ItemValueLabel
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
			list.insert(0, InfoList.withi("obj", Icon.infoSmall, () -> Viewers.toString(val))
			 .color(Viewers.colorOf(val)));
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
	//endregion
}