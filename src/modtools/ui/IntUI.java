
package modtools.ui;

import arc.Core;
import arc.Graphics.Cursor;
import arc.Graphics.Cursor.SystemCursor;
import arc.backend.sdl.SdlGraphics.SdlCursor;
import arc.backend.sdl.jni.SDL;
import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.input.KeyCode;
import arc.math.Interp;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.actions.Actions;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import arc.util.pooling.Pools;
import mindustry.Vars;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.*;
import mindustry.ui.*;
import modtools.IntVars;
import modtools.content.SettingsUI;
import modtools.content.SettingsUI.SettingsBuilder;
import modtools.content.debug.Tester;
import modtools.jsfunc.INFO_DIALOG;
import modtools.struct.LazyValue;
import modtools.ui.TopGroup.*;
import modtools.ui.comp.*;
import modtools.ui.comp.Window.*;
import modtools.ui.control.HopeInput;
import modtools.ui.windows.*;
import modtools.ui.windows.NameWindow.FileNameWindow;
import modtools.utils.*;
import modtools.utils.ArrayUtils.DisposableSeq;
import modtools.utils.JSFunc.*;
import modtools.utils.search.Search;
import modtools.utils.ui.*;

import java.util.Objects;
import java.util.regex.Pattern;

import static arc.Core.graphics;
import static mindustry.Vars.*;
import static modtools.IntVars.mouseVec;
import static modtools.utils.ElementUtils.getAbsolutePos;

@SuppressWarnings("UnusedReturnValue")
public class IntUI {
	public static final TextureRegionDrawable whiteui = (TextureRegionDrawable) Tex.whiteui;

	/** pad 8 */
	public static final Drawable emptyui = new EmptyDrawable(8);
	/** pad 0 */
	public static final Drawable noneui  = new EmptyDrawable(0);

	public static final float DEFAULT_WIDTH  = 180;
	public static final float MAX_LONGPRESS_OFF = 6f;
	public static final float MAX_DCLICK_OFF = 35f;

	public static Frag     frag;
	public static TopGroup topGroup;

	public static final int FUNCTION_BUTTON_SIZE = 42;

	private static final LazyValue<ColorPicker> _c = LazyValue.of(ColorPicker::new);
	public static ColorPicker colorPicker() {
		return _c.get();
	}
	private static final LazyValue<DrawablePicker> _d = LazyValue.of(DrawablePicker::new);
	public static DrawablePicker drawablePicker() {
		return _d.get();
	}
	private static final LazyValue<FileNameWindow> _1 = LazyValue.of(FileNameWindow::new);
	public static FileNameWindow fileNameWindow() {
		return _1.get();
	}

	public static Cursor northwestToSoutheast = newCursor(SDL.SDL_SYSTEM_CURSOR_SIZENWSE); // northwest to southeast
	public static Cursor southwestToNortheast = newCursor(SDL.SDL_SYSTEM_CURSOR_SIZENESW); // southwest to northeast

	/**
	 * Load.
	 */
	public static void load() {
		if (frag == null) frag = new Frag();
		if (topGroup == null) topGroup = new TopGroup();

		if (frag.getChildren().isEmpty()) {
			frag.load();
		} else {
			topGroup.addChild(frag);
		}
	}
	private static Cursor newCursor(int type) {
		if (OS.isAndroid) return SystemCursor.arrow;
		long handle = SDL.SDL_CreateSystemCursor(type);
		return new SdlCursor(0, handle);
	}
	public static void disposeAll() {
		topGroup.dispose();
		frag.clear();
		Background.dispose();
	}

	/** 默认的动效时间（单位秒） */
	public static final float DEF_DURATION  = 0.2f;
	/** 默认的长按触发时间（单位ms） */
	public static final long  DEF_LONGPRESS = 600L;


	/** @see ShowInfoWindow */
	public static void addLabelButton(Table table, Prov<?> prov, @Nullable Class<?> clazz) {
		addDetailsButton(table, prov, clazz);
		// addStoreButton(table, Core.bundle.get("jsfunc.value", "value"), prov);
	}
	public static ImageButton addDetailsButton(Table table, Prov<?> prov, @Nullable Class<?> clazz) {
		return table.button(Icon.infoCircleSmall, HopeStyles.clearNonei, 28, IntVars.EMPTY_RUN)
		 .with(button -> EventHelper.longPress(button, isLongPress -> {
			 Object o = prov.get();
			 if (o == null && clazz == null) return;
			 Core.app.post(Tools.runT0(() -> {
				 if (isLongPress && o instanceof Class<?> oClass) {
					 INFO_DIALOG.showInfo(oClass);
				 } else {
					 INFO_DIALOG.showInfo(o);
				 }
			 }));
		 }))
		 .with(makeTipListener("details_button"))
		 .size(FUNCTION_BUTTON_SIZE, FUNCTION_BUTTON_SIZE)
		 .disabled(_ -> (clazz == null || clazz.isPrimitive()) && prov.get() == null)
		 .get();
	}

	public static void addStoreButton(Table table, String key, Prov<?> prov) {
		table.button(buildStoreKey(key),
			HopeStyles.flatBordert, IntVars.EMPTY_RUN).padLeft(8f).size(180, 40)
		 .with(b -> b.clicked(() -> Tester.put(b, prov.get())));
	}
	public static String buildStoreKey(String key) {
		return key == null || key.isEmpty() ? Core.bundle.get("jsfunc.store_as_js_var2")
		 : Core.bundle.format("jsfunc.store_as_js_var", key);
	}

	/**
	 * Add watch button cell.
	 * @return the cell
	 */
	public static Cell<?> addWatchButton(Table buttons, String info, MyProv<Object> value) {
		return addWatchButton(buttons, () -> info, value);
	}
		/**
	 * Add watch button cell.
	 * @return the cell
	 */
	public static Cell<?> addWatchButton(Table buttons, Prov<String> info, MyProv<Object> value) {
		return buttons.button(Icon.eyeSmall, HopeStyles.clearNonei, IntVars.EMPTY_RUN).with(b -> b.clicked(() -> {
			SR.of((!WatchWindow.isMultiWatch() &&
			       ArrayUtils.findInverse(topGroup.acquireShownWindows(), e -> e instanceof WatchWindow) instanceof WatchWindow w
				? w : JSFunc.watch())
				.watch(info.get(), value))
			 .cons(WatchWindow::isEmpty, t -> t.setPosition(getAbsolutePos(b)));
		})).size(FUNCTION_BUTTON_SIZE).with(makeTipListener("watch.multi"));
	}



	/**
	 * @param prov the prov
	 * @return Runnable：将prov的值储存为js变量
	 */
	public static Runnable storeRun(Prov<Object> prov) {
		return () -> Tester.put(mouseVec, prov.get());
	}

	/**
	 * 在鼠标右下弹出一个小窗，自己设置内容
	 * @param f          (p, hide, text)                   p 是Table，你可以添加元素                   hide 是一个函数，调用就会关闭弹窗                   text 如果 @param 为 true ，则启用。用于返回用户在搜索框输入的文本
	 * @param searchable 可选，启用后会添加一个搜索框
	 * @return the table
	 */
	public static SelectTable
	showSelectTableRB(Builder f,
	                  boolean searchable) {
		SelectTable t = basicSelectTable(mouseVec, searchable, f);
		float       x = mouseVec.x, y = mouseVec.y;
		t.update(() -> {
			t.setPosition(x, y, Align.topLeft);
			checkBound(t);
		});
		return t;
	}

	public static <T extends Element> SelectTable
	showSelectTable(T button, Builder builder,
	                boolean searchable) {
		return showSelectTable(button, builder, searchable, Align.bottom);
	}
	/**
	 * 弹出一个小窗，自己设置内容
	 * @param <T>        the type parameter
	 * @param button     用于定位弹窗的位置
	 * @param builder    (p, hide, text)                   p 是Table，你可以添加元素                   hide 是一个函数，调用就会关闭弹窗                   text 如果 @param 为 true ，则启用。用于返回用户在搜索框输入的文本
	 * @param searchable 可选，启用后会添加一个搜索框
	 * @param align      the align
	 * @return the select table
	 */
	public static <T extends Element> SelectTable
	showSelectTable(T button, Builder builder,
	                boolean searchable, int align) {
		if (button == null) throw new NullPointerException("button cannot be null");
		SelectTable t = basicSelectTable(button, searchable, builder);
		t.background(Tex.pane);
		// t.actions(Actions.sizeTo(0, 0), Actions.sizeTo(t.getPrefWidth(), t.getPrefHeight(), 12 ));
		t.update(() -> {
			if (button.parent == null || !button.isDescendantOf(Core.scene.root)) {
				IntVars.postToMain(t::hideInternal);
				return;
			}
			int lyingAlign = align;
			if (Align.isCenterVertical(align)) {
			} else if (Align.isTop(align)) lyingAlign = lyingAlign & ~Align.top | Align.bottom;
			else if (Align.isBottom(align)) lyingAlign = lyingAlign & ~Align.bottom | Align.top;

			if (Align.isCenterHorizontal(align)) {
			} else if (Align.isLeft(align)) lyingAlign = lyingAlign & ~Align.left | Align.right;
			else if (Align.isRight(align)) lyingAlign = lyingAlign & ~Align.right | Align.left;

			checkBound(t);
			positionTooltip(button, lyingAlign, t, align);
		});
		return t;
	}
	public static void positionTooltip(Element lying, Table t) {
		positionTooltip(lying, Align.bottom, t, Align.top);
	}

	public static boolean xlock, ylock;
	public static void positionTooltip(Element lying, int lyingAlign, Table table, int tableAlign) {
		Vec2 pos = Tmp.v1;
		lying.localToStageCoordinates(
		 pos.set(lying.getX(lyingAlign) - lying.x,
			lying.getY(lyingAlign) - lying.y));

		table.setPosition(pos.x, pos.y, tableAlign);
		// 在不遮挡lying的情况下，如果上面超出屏幕
		if (!ylock && (table.y + table.getHeight() > Core.graphics.getHeight() || table.y < 0)) {
			int lyingAlign1 = Align.isTop(lyingAlign) ? lyingAlign & ~Align.top | Align.bottom : lyingAlign & ~Align.bottom | Align.top;
			int tableAlign1 = Align.isTop(tableAlign) ? tableAlign & ~Align.top | Align.bottom : tableAlign & ~Align.bottom | Align.top;
			ylock = true;
			positionTooltip(lying, lyingAlign1, table, tableAlign1);
			ylock = false;
		}

		// 在不遮挡lying的情况下，如果右边超出屏幕
		if (!xlock && (table.x + table.getWidth() > Core.graphics.getWidth() || table.x < 0)) {
			int lyingAlign1 = Align.isLeft(lyingAlign) ? lyingAlign & ~Align.left | Align.right : lyingAlign & ~Align.right | Align.left;
			int tableAlign1 = Align.isLeft(tableAlign) ? tableAlign & ~Align.left | Align.right : tableAlign & ~Align.right | Align.left;
			xlock = true;
			positionTooltip(lying, lyingAlign1, table, tableAlign1);
			xlock = false;
		}

		// keep in stage
		/** @see Element#keepInStage()  */
		float width  = graphics.getWidth();
		float height = graphics.getHeight();
		if (table.getX(Align.right) > width)
			table.setPosition(width, table.getY(Align.right), Align.right);
		if (table.getX(Align.left) < 0)
			table.setPosition(0, table.getY(Align.left), Align.left);
		if (table.getY(Align.top) > height)
			table.setPosition(table.getX(Align.top), height, Align.top);
		if (table.getY(Align.bottom) < 0)
			table.setPosition(table.getX(Align.bottom), 0, Align.bottom);

		// Log.info("@, @",table.x, table.y);
	}
	public static SelectTable basicSelectTable(Vec2 vec2, boolean searchable, Builder builder) {
		return basicSelectTable(mouseVec.equals(vec2) ? HopeInput.mouseHit() : null, searchable, builder);
	}
	public static SelectTable basicSelectTable(Element button, boolean searchable, Builder builder) {
		Table p = new Table();
		p.top();

		SelectTable t = new SelectTable(p, button);

		// 淡入
		t.actions(Actions.alpha(0f), Actions.fadeIn(0.1f, Interp.fade));

		Runnable hide  = t::hideInternal;
		Runnable hide0 = mergeHide(t, hide);
		if (searchable) {
			newSearch(builder, hide0, t, p);
		} else {// newSearch会自动构建一次
			builder.get(p.row(), hide0, PatternUtils.ANY/* see Search */);
		}
		t.init();
		t.appendToGroup();

		return t;
	}
	private static Runnable mergeHide(SelectTable t, Runnable hide) {
		return () -> (t.hide != null ? t.hide : hide).run();
	}
	private static void newSearch(Builder rebuild, Runnable hide,
	                              SelectTable title, Table container) {
		new Search<>((cont, text) -> rebuild.get(cont, hide, text))
		 .build(title, container);
	}

	public static <T extends Element> SelectTable
	showSelectListTable(T button, Seq<String> list, Prov<String> holder,
	                    Cons<String> cons, int width, int height,
	                    boolean searchable, int align) {
		return showSelectListTable(button, list, holder, cons, s -> s, width, height, searchable, align);
	}
	public static <T extends Element, E extends Enum<E>> SelectTable
	showSelectListEnumTable(T button, Seq<E> list, Prov<E> holder,
	                        Cons<E> cons, float width, float height,
	                        boolean searchable, int align) {
		return showSelectListTable(button, list, holder, cons,
		 Enum::name, width, height, searchable, align);
	}

	/** @see modtools.ui.comp.utils.MyItemSelection#buildTable0(Table, Seq, Prov, Cons, int, Func) */
	public static <BTN extends Element, V> SelectTable
	showSelectListTable(
	 BTN button, Seq<V> list, Prov<V> holder,
	 Cons<V> cons, Func<V, String> stringify, float minWidth, float height,
	 boolean searchable, int align) {
		SelectTable table = showSelectTable(button, (p, hide, pattern) -> {
			p.clearChildren();

			for (V item : list) {
				if (!PatternUtils.test(pattern, stringify.get(item))) continue;
				p.button(stringify.get(item), HopeStyles.cleart/*Styles.cleart*/, () -> {
					 cons.get(item);
					 hide.run();
				 }).with(t ->
					t.getLabelCell()
					 .padLeft(8f).padRight(8)
					 .labelAlign(Align.left)
				 ).wrapLabel(false)
				 .minWidth(minWidth).growX().height(height)
				 .disabled(_ -> Objects.equals(holder.get(), item)).row();

				p.image().color(Tmp.c1.set(JColor.c_underline)).growX().row();
			}
		}, searchable, align);
		table.hidden(() -> {
			if (list instanceof DisposableSeq) {
				Pools.free(list);
			}
		});
		return table;
	}

	/**
	 * 弹出一个可以选择内容的窗口（类似物品液体源的选择）
	 * （需要提供图标）
	 * @param <T>        the type parameter
	 * @param <T1>       the type parameter
	 * @param button     the button
	 * @param items      用于展示可选的内容
	 * @param icons      可选内容的图标
	 * @param holder     选中的内容，null就没有选中任何
	 * @param cons       选中内容就会调用
	 * @param size       每个内容的元素大小
	 * @param imageSize  每个内容的图标大小
	 * @param cols       一行的元素数量
	 * @param searchable the searchable
	 * @return the table
	 */
	public static <T extends Button, T1> SelectTable
	showSelectImageTableWithIcons(T button, Seq<T1> items,
	                              Seq<? extends Drawable> icons,
	                              Prov<T1> holder, Cons<T1> cons, float size,
	                              float imageSize, int cols,
	                              boolean searchable) {
		return showSelectTable(button, builderWithIcons(items, icons, holder, cons, size, imageSize, cols), searchable, Align.center);
	}

	public static <T1> SelectTable
	showSelectImageTableWithIcons(Vec2 vec2, Seq<T1> items,
	                              Seq<? extends Drawable> icons,
	                              Prov<T1> holder, Cons<T1> cons, float size,
	                              float imageSize, int cols,
	                              boolean searchable) {
		return showSelectTable(vec2, builderWithIcons(items, icons, holder, cons, size, imageSize, cols), searchable);
	}
	private static <T1> Builder builderWithIcons(
	 Seq<T1> items, Seq<? extends Drawable> icons,
	 Prov<T1> holder, Cons<T1> cons, float size, float imageSize, int cols) {
		return (p, hide, pattern) -> {
			boolean[] notHideAuto = {false};
			Runnable wrapperHide = () -> {
				if (!notHideAuto[0]) hide.run();
			};
			p.clearChildren();
			p.left();
			ButtonGroup<ImageButton> group = new ButtonGroup<>();
			group.setMinCheckCount(0);
			p.defaults().size(size);

			int c = 0;
			for (int i = 0; i < items.size; i++) {
				T1 item = items.get(i);
				if (!PatternUtils.testAny(pattern, item)) continue;

				ImageButton btn = Hover.buildImageButton(cons, size, imageSize, p, wrapperHide, item, icons.get(i));
				btn.update(() -> btn.setChecked(holder.get() == item));

				if (++c % cols == 0) {
					p.row();
				}
			}
			SettingsBuilder.build(p);
			p.row().defaults().colspan(cols).size(CellTools.unset);
			// see JSRequest
			SettingsBuilder.check("@jsrequest.nothideauto", b -> notHideAuto[0] = b, () -> notHideAuto[0]);
			p.row();
			SettingsBuilder.clearBuild();
		};
	}

	public static SelectTable
	showSelectTable(Vec2 vec2, Builder f,
	                boolean searchable) {
		SelectTable t = basicSelectTable(vec2, searchable, f);
		t.background(Tex.pane);
		t.update(() -> {
			t.setPosition(vec2.x, vec2.y, 1);
			checkBound(t);
		});
		return t;
	}
	public static void checkBound(SelectTable t) {
		if (t.getWidth() > Core.scene.getWidth()) {
			t.setWidth((float) graphics.getWidth());
		}

		if (t.getHeight() > Core.scene.getHeight()) {
			t.setHeight((float) graphics.getHeight());
		}

		t.keepInStage();
		t.invalidateHierarchy();
		t.pack();
	}


	/** 弹出一个可以选择内容的窗口（无需你提供图标，需要 <i>{@link UnlockableContent}</i>） */
	public static <T1 extends UnlockableContent> SelectTable
	showSelectImageTable(Vec2 vec2, Seq<T1> items,
	                     Prov<T1> holder,
	                     Cons<T1> cons, float size,
	                     float imageSize, int cols,
	                     boolean searchable) {
		return showSelectImageTableWithFunc(vec2, items, holder, cons, size, imageSize, cols,
		 IntUI::icon, searchable);
	}

	/** 弹出一个可以选择内容的窗口（需你提供{@link Func 图标构造器}） */
	public static <T1> SelectTable
	showSelectImageTableWithFunc(Vec2 vec2, Seq<T1> items, Prov<T1> holder,
	                             Cons<T1> cons, float size, float imageSize,
	                             int cols, Func<T1, Drawable> func,
	                             boolean searchable) {
		Seq<Drawable> icons = new Seq<>(items.size);
		items.each(item -> icons.add(func.get(item)));
		return showSelectImageTableWithIcons(vec2, items, icons, holder, cons, size, imageSize, cols, searchable);
	}
	public static <T extends Button, T1> SelectTable
	showSelectImageTableWithFunc(T button, Seq<T1> items, Prov<T1> holder,
	                             Cons<T1> cons, float size, float imageSize,
	                             int cols, Func<T1, Drawable> func,
	                             boolean searchable) {
		Seq<Drawable> icons = new Seq<>(items.size);
		items.each(item -> icons.add(func.get(item)));
		return showSelectImageTableWithIcons(button, items, icons, holder, cons, size, imageSize, cols, searchable);
	}


	/** 按shift键，忽略确认 */
	public static void shiftIgnoreConfirm(String text, Runnable run) {
		if (Core.input.shift()) {
			run.run();
		} else {
			IntUI.showConfirm(Core.bundle.format("confirm.remove", text), run);
		}
	}

	// ----window-----
	/** Window弹窗错误 */
	public static Window showException(Throwable t) {
		return showException("", t);
	}
	public static Window showException(String text, Throwable exc) {
		ui.loadfrag.hide();
		return ExceptionPopup.of(exc, text);
	}

	public static Window showInfoFade(String info) {
		return showInfoFade(info, mouseVec);
	}

	public static Window showInfoFade(String info, Vec2 pos) {
		return showInfoFade(info, pos, Align.center);
	}
	public static Window showInfoFade(String info, Vec2 pos, int align) {
		return new InfoFadePopup("Info", 120, 64) {{
			cont.add(info);
			// 1.2s
			Time.runTask(60 * 1.4f, this::hide);
		}}.show().setPosition(pos, align);
	}

	/** @see mindustry.core.UI#showConfirm(String, Runnable) */
	public static ConfirmWindow showConfirm(String text, Runnable confirmed) {
		return showConfirm("@confirm", text, null, confirmed);
	}
	/** @see mindustry.core.UI#showConfirm(String, String, Runnable) */
	public static ConfirmWindow showConfirm(String title, String text, Runnable confirmed) {
		return showConfirm(title, text, null, confirmed);
	}
	/**
	 * @see mindustry.core.UI#showConfirm(String, String, Boolp, Runnable)
	 */
	public static ConfirmWindow showConfirm(String title, String text, Boolp hide,
	                                        Runnable confirmed) {
		ConfirmWindow window = new ConfirmWindow(title, 0, 100, false, false);
		window.cont.add(text).width(mobile ? 400f : 500f).wrap().pad(4f).get().setAlignment(Align.center, Align.center);
		window.buttons.defaults().size(200f, 54f).pad(2f);
		window.setFillParent(false);
		window.buttons.button("@cancel", Icon.cancel, window::hide);
		window.buttons.button("@ok", Icon.ok, () -> {
			window.hide();
			confirmed.run();
		});
		if (hide != null) {
			window.update(() -> {
				if (hide.get()) {
					window.hide();
				}
			});
		}
		window.requestKeyboard();
		window.keyDown(KeyCode.enter, () -> {
			window.hide();
			confirmed.run();
		});
		window.keyDown(KeyCode.escape, window::hide);
		window.keyDown(KeyCode.back, window::hide);
		return window;
	}
	/**
	 * @param denied 如果不为{@code null}，右上角关闭按钮会被删除
	 *  @see mindustry.core.UI#showCustomConfirm(String, String, String, String, Runnable, Runnable) */
	public static ConfirmWindow showCustomConfirm(String title, String text, String yes, String no, Runnable confirmed,
	                                              Runnable denied) {
		ConfirmWindow window = new ConfirmWindow(title, 400, 100, false, false);
		if (denied != null) window.closeButton.remove();
		window.cont.add(text).width(Vars.mobile ? 400f : 500f).wrap().pad(4).get().setAlignment(Align.center, Align.center);
		window.buttons.defaults().size(200f, 54f).pad(2);
		window.setFillParent(false);
		window.buttons.button(no, () -> {
			window.hide();
			if (denied != null) denied.run();
		});
		window.buttons.button(yes, () -> {
			window.hide();
			confirmed.run();
		});
		window.keyDown(KeyCode.escape, window::hide);
		window.keyDown(KeyCode.back, window::hide);
		return window;
	}


	public static <U extends UnlockableContent> Drawable icon(U i) {
		return new TextureRegionDrawable(i == null || i.uiIcon == null ? Core.atlas.find("error") : i.uiIcon);
	}


	public static class ColorContainer extends BorderImage {
		private Color colorValue;
		/**
		 * Instantiates a new Color container.
		 * @param color the color
		 */
		public ColorContainer(Color color) {
			super(Core.atlas.white(), 2f);

			changeColor(colorValue = color);
			update(() -> changeColor(colorValue));
		}
		private void changeColor(Color color) {
			setColor(color);
			border(Tmp.c1.set(color).inv());
		}
		public void draw() {
			Draw.color();
			float alpha = Draw.getColor().a;
			Draw.alpha(parentAlpha);
			Tex.alphaBg.draw(x, y, width, height);
			Draw.alpha(alpha);
			super.draw();
		}
		/**
		 * Sets color value.
		 * @param color the color
		 */
		public void setColorValue(Color color) {
			colorValue = color;
		}
	}


	public static void addCheck(Cell<? extends ImageButton> cell, Boolp boolp,
	                            String valid, String invalid) {
		cell.get().addListener(new ITooltip(() -> boolp.get() ? valid : invalid));
		cell.update(b -> b.getStyle().imageUpColor = boolp.get() ? Color.white : Color.gray);
	}

	/** TIP_PREFIX: {@value TIP_PREFIX} */
	public static <T extends Element> Cons<T> makeTipListener(String tipKey) {
		return elem -> addTooltipListener(elem, () -> tips(tipKey));
	}
	public static void addTooltipListener(Element element, Prov<CharSequence> text) {
		element.addListener(new ITooltip(text));
	}
	public static final String TIP_PREFIX = "mod-tools.tips.";
	public static boolean hasTips(String key) {
		return Core.bundle.has(TIP_PREFIX + key);
	}
	/**
	 * TIP_PREFIX: {@value TIP_PREFIX}
	 * @see SettingsUI#tryAddTip(Element, String)
	 */
	public static String tips(String key) {
		return Core.bundle.format("mod-tools.tips", FormatHelper.parseVars(Core.bundle.get(TIP_PREFIX + key)));
	}
	/**
	 * TIP_PREFIX: {@value TIP_PREFIX}
	 * @see SettingsUI#tryAddTip(Element, String)
	 */
	public static String tips(String key, String arg1) {
		return Core.bundle.format("mod-tools.tips", FormatHelper.parseVars(Core.bundle.format(TIP_PREFIX + key, arg1)));
	}


	public static final
	Color DEF_MASK_COLOR = Color.black.cpy().a(0.5f),
	 DEF_FOCUS_COLOR     = Color.blue.cpy().a(0.4f);

	/**
	 * 聚焦一个元素
	 * @param element 要聚焦的元素
	 * @param boolp   {@link Boolp#get()}的返回值如果为{@code false}则移除聚焦
	 */
	public static void focusOnElement(Element element, Boolp boolp) {
		topGroup.focusOnElement(new MyFocusTask(element, boolp));
	}

	public static void hoverAndExit(Element element, Runnable hovered, Runnable exit) {
		element.addListener(new HoverAndExitListener() {
			public void enter0(InputEvent event, float x, float y, int pointer, Element fromActor) {
				hovered.run();
			}
			public void exit0(InputEvent event, float x, float y, int pointer, Element toActor) {
				exit.run();
			}
		});
	}

	public static class HoverAndExitListener extends InputListener {
		/**
		 * 是否忽略{@code group}内元素的进出<br>
		 * 如果为{@code true}，c绑定的侦听器，a -> b时不会触发enter & exit
		 * <table style="border: 1px solid #ccf">
		 *   c
		 *   <td style="border: 1px solid #fcc">a</td>
		 *   <td style="border: 1px solid #fcc">b</td>
		 * </table>
		 */
		private boolean ignoreInsideElement = false;
		public HoverAndExitListener(boolean ignoreInsideElement) {
			this.ignoreInsideElement = ignoreInsideElement;
		}
		public HoverAndExitListener() { }
		/** {@inheritDoc} */
		public final void enter(InputEvent event, float x, float y, int pointer, Element fromActor) {
			// 如果inElement为true，判断fromActor是否是绑定元素的 子元素（Descendant）
			if (ignoreInsideElement && (fromActor == null || !fromActor.isDescendantOf(event.listenerActor))) return;
			// touchDown也会触发
			if (Core.input.isTouched() == (pointer != -1)) enter0(event, x, y, pointer, fromActor);
		}
		public void enter0(InputEvent event, float x, float y, int pointer, Element fromActor) { }

		/** {@inheritDoc} */
		public final void exit(InputEvent event, float x, float y, int pointer, Element toActor) {
			// 如果inElement为true，判断fromActor是否是绑定元素的 子元素（Descendant）
			if (ignoreInsideElement && (toActor == null || !toActor.isDescendantOf(event.listenerActor))) return;
			// touchUp也会触发
			if (Core.input.isTouched() == (pointer != -1)) exit0(event, x, y, pointer, toActor);
		}
		public void exit0(InputEvent event, float x, float y, int pointer, Element toActor) { }
	}

	public static class ITooltip extends Tooltip implements IInfo {
		public static final Seq<Tooltip> shown = new Seq<>();
		long lastShowTime;

		public ITooltip(Cons<Table> contents) {
			super(t -> { });
			allowMobile = true;
			/* 异步执行时，字体会缺失  */
			show = () -> {
				lastShowTime = Time.millis();
				Table container = this.container;
				if (container.getChildren().isEmpty()) contents.get(container);
				container.update(container::pack);
				topGroup.addChild(container);
				shown.add(this);

				container.margin(10f);
			};
		}
		public void show(Element element, float x, float y) {
			// 在新版本，移除了这个方法
			// Tools.runIgnoredException(() -> getManager().hideAll());
			for (Tooltip tooltip : shown) {
				if (tooltip.container.parent == topGroup) tooltip.hide();
			}
			super.show(element, x, y);
		}
		public void hide() {
			shown.remove(this);
			if (mobile) {
				TaskManager.scheduleOrReset(1.2f - Time.timeSinceMillis(lastShowTime) / 1000f, super::hide);
			} else {
				super.hide();
			}
		}
		public ITooltip(Prov<CharSequence> prov) {
			this(t -> t.background(Styles.black6).margin(6f).label(prov));
		}
		public ITooltip(Cons<Table> contents, Runnable show) {
			super(contents, show);
		}
		public ITooltip(Cons<Table> contents, Tooltips manager) {
			super(contents, manager);
		}
		protected void setContainerPosition(Element element, float x, float y) {
			this.targetActor = element;
			IntUI.positionTooltip(element, Align.top, container, Align.bottom);
		}

		static {
			Tooltips.getInstance().textProvider = text -> new ITooltip(() -> text);
		}
	}

	// ======-----弹窗------======
	/* 这会添加到others里 */
	public interface PopupWindow extends INotice { }
	/* 代表Menu菜单 */
	public interface IMenu extends IDisposable, IInfo { }
	public interface IHitter extends IInfo { }
	/* 代表一个通知 */
	public interface INotice extends IInfo { }

	public static class InfoFadePopup extends NoTopWindow implements DelayDisposable {
		/**
		 * Instantiates a new Info fade popup.
		 * @param title  the title
		 * @param width  the width
		 * @param height the height
		 */
		public InfoFadePopup(String title, float width, float height) {
			super(title, width, height);
			sclListener.remove();
		}
		public void clearAll() {
			Time.runTask(10f, DelayDisposable.super::clearAll);
		}
	}
	private static class ExceptionPopup extends Window implements PopupWindow, IDisposable {
		static final ObjectMap<Signature, ExceptionPopup> instances = new ObjectMap<>();
		private ExceptionPopup(Signature signature, Throwable th) {
			super("", 0, 200, false);
			String text = signature.text, message = signature.message;
			instances.put(signature, this);
			hidden(() -> instances.remove(signature));

			cont.margin(15);
			cont.add("@error.title").colspan(2);
			cont.row();
			cont.image().width(300f).pad(2).colspan(2).height(4f).color(Color.scarlet);
			cont.row();
			cont.add(text == null ? "" : (text.startsWith("@") ? Core.bundle.get(text.substring(1)) : text) + (message == null ? "" : "\n[lightgray](" + message + ")"))
			 .colspan(2).wrap().growX().center()
			 .get().setAlignment(Align.center);
			cont.row();

			Collapser col = new Collapser(base -> base.pane(t -> t.margin(14f).add(Strings.neatError(th)).color(Color.lightGray).left()), true);

			cont.button("@details", Styles.togglet, col::toggle).size(180f, 50f).checked(b -> !col.isCollapsed()).growX().right();
			col.setDuration(0.2f);
			cont.button("@ok", this::hide).size(110, 50).growX().left();
			cont.row();
			col.setCollapsed(false, false);
			cont.add(col).colspan(2).pad(2);
			// closeOnBack();
		}
		public static Window of(Throwable th, String text) {
			return get(th, text).show();
		}
		private static Window get(Throwable th, String text) {
			Signature signature = new Signature(Strings.getFinalMessage(th), text);
			if (instances.containsKey(signature)) return instances.get(signature);
			return new ExceptionPopup(signature, th);
		}
		static class Signature {
			String message, text;
			public Signature(String message, String text) {
				this.message = message;
				this.text = text;
			}
			public boolean equals(Object obj) {
				if (!(obj instanceof Signature sig)) return false;
				return Objects.equals(sig.message, message) && Objects.equals(sig.text, text);
			}
			public int hashCode() {
				return Objects.hash(message, text);
			}
		}
	}
	public static class ConfirmWindow extends Window implements IDisposable, PopupWindow, IHitter {
		/**
		 * Instantiates a new Confirm window.
		 * @param title     the title
		 * @param minWidth  the min width
		 * @param minHeight the min height
		 * @param full      the full
		 * @param noButtons the no buttons
		 */
		public ConfirmWindow(String title, float minWidth, float minHeight, boolean full,
		                     boolean noButtons) {
			super(title, minWidth, minHeight, full, noButtons);
		}
		public ConfirmWindow(String title) {
			super(title);
		}
		{
			shown(() -> {
				hitter.touchable = Touchable.enabled;
				hitter.clear();
				hitter.autoClose = false;
			});
		}

		/**
		 * Sets center.
		 * @param vec2 the vec 2
		 */
		public void setCenter(Vec2 vec2) {
			setPosition(vec2.x - getPrefWidth() / 2f, vec2.y - getPrefHeight() / 2f);
		}
	}

	private static class AutoFitTable extends Table implements PopupWindow {
		public float getPrefHeight() {
			return Math.min(super.getPrefHeight(), (float) graphics.getHeight());
		}

		public float getPrefWidth() {
			return Math.min(super.getPrefWidth(), (float) graphics.getWidth());
		}
	}
	public static class SelectTable extends AutoFitTable implements IMenu {
		public SelectTable(Table table, Element button) {
			this.table = table;
			this.button = button;
		}
		public void init() {
			ScrollPane pane = new ScrollPane(table, Styles.smallPane);
			top().add(pane).grow().pad(0f).top();
			ElementUtils.hideBarIfValid(pane);
		}
		Hitter hitter = new Hitter(this::hideInternal);
		final void hideInternal() {
			actions(Actions.fadeOut(DEF_DURATION, Interp.fade),
			 Actions.run(() -> fire(new VisibilityEvent(true))),
			 Actions.remove());
		}
		public final     Table    table;
		public final     Element  button;
		/**
		 * <p>为{@code null}时，使用默认隐藏{@link #hideInternal()}</p>
		 * <p>仅用于builder参数的hide，内部依然是直接隐藏（即默认值）</p>
		 */
		public @Nullable Runnable hide;
		/**
		 * Adds a hide() listener.
		 */
		public void hidden(Runnable run) {
			addListener(new VisibilityListener() {
				@Override
				public boolean hidden() {
					run.run();
					return false;
				}
			});
		}

		public void appendToGroup() {
			topGroup.addChild(hitter);
			topGroup.addChild(this);
		}
	}

	public interface Builder extends Cons3<Table, Runnable, Pattern> { }

	private static class MyFocusTask extends FocusTask {
		private final Boolp boolp;
		public MyFocusTask(Element element, Boolp boolp) {
			super(element, IntUI.DEF_MASK_COLOR, Color.clear);
			this.boolp = boolp;
		}
		public void backDraw() {
			super.backDraw();
			if (!boolp.get()) topGroup.removeFocusElement(this);
		}
		public void drawFocus(Element elem) {
			super.drawFocus(elem);
			// Draw.blit(ScreenSampler(getAbsolutePos(elem), elem), baseShader);
		}
		public void elemDraw() { }
		public void endDraw() {
			super.endDraw();
			drawFocus(elem);
		}
	}

	private static class EmptyDrawable extends BaseDrawable {
		public final float pad;
		public EmptyDrawable(float pad) {
			this.pad = pad;
			setTopHeight(pad);
			setLeftWidth(pad);
			setBottomHeight(pad);
			setRightWidth(pad);
		}
	}
}