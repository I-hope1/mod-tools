
package modtools.ui;

import arc.Core;
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
import arc.struct.Seq;
import arc.util.*;
import arc.util.Timer.Task;
import arc.util.pooling.Pools;
import mindustry.Vars;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.*;
import mindustry.ui.*;
import modtools.ui.TopGroup.FocusTask;
import modtools.ui.components.*;
import modtools.ui.components.Window.*;
import modtools.ui.menu.*;
import modtools.ui.menus.*;
import modtools.ui.windows.ColorPicker;
import modtools.utils.*;
import modtools.utils.JSFunc.MyProv;
import modtools.utils.ui.*;
import modtools.utils.ui.search.*;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static arc.Core.graphics;
import static mindustry.Vars.*;
import static modtools.graphics.MyShaders.baseShader;
import static modtools.ui.Contents.tester;
import static modtools.ui.effect.ScreenSampler.bufferCaptureAll;
import static modtools.utils.ElementUtils.getAbsolutePos;
import static modtools.utils.Tools.*;

public class IntUI {
	public static final TextureRegionDrawable whiteui = (TextureRegionDrawable) Tex.whiteui;

	public static final float DEFAULT_WIDTH = 150;
	public static final float MAX_OFF       = 35f;

	public static final Frag        frag   = new Frag();
	public static final TopGroup    topGroup;
	public static       ColorPicker picker = new ColorPicker();

	static {
		topGroup = new TopGroup();
	}

	/**
	 * Load.
	 */
	public static void load() {
		if (frag.getChildren().isEmpty()) {
			frag.load();
		} else {
			topGroup.addChild(frag);
		}
	}

	/** 默认的动效时间（单位秒） */
	public static final float DEF_DURATION  = 0.2f;
	/** 默认的长按触发时间（单位ms） */
	public static final long  DEF_LONGPRESS = 600L;

	static final Vec2 last = new Vec2();
	/**
	 * <p>创建一个双击事件</p>
	 * <p color="gray">我还做了位置偏移计算，防止误触</p>
	 * @param <T>  the type parameter
	 * @param elem 被添加侦听器的元素
	 * @param click 单击事件
	 * @param d_click 双击事件
	 * @return the t
	 */
	public static <T extends Element> T
	doubleClick(T elem, Runnable click, Runnable d_click) {
		if (click == null && d_click == null) return elem;
		class ClickTask extends Task {
			public void run() {
				if (click != null) click.run();
			}
		}
		class DoubleClick extends ClickListener {
			final Task clickTask = new ClickTask();
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				last.set(Core.input.mouse());
				return super.touchDown(event, x, y, pointer, button);
			}
			public void clicked(InputEvent event, float x, float y) {
				if (last.dst(Core.input.mouse()) > MAX_OFF) return;
				super.clicked(event, x, y);
				if (click != null && d_click == null) {
					click.run();
					return;
				}
				Vec2 mouse = Core.input.mouse();
				if (TaskManager.reScheduled(0.3f, clickTask)) {
					last.set(mouse);
					return;
				}
				if (mouse.dst(last) < MAX_OFF) d_click.run();
			}
		}
		elem.addListener(new DoubleClick());
		return elem;
	}


	/**
	 * 长按事件
	 * @param <T>  the type parameter
	 * @param elem 被添加侦听器的元素
	 * @param duration 需要长按的事件（单位毫秒[ms]，600ms=0.6s）
	 * @param boolc {@link Boolc#get(boolean b)}形参{@code b}为是否长按
	 * @return the t
	 */
	public static <T extends Element> T
	longPress(T elem, final long duration, final Boolc boolc) {
		class LongPressListener extends ClickListener {
			class LongPressTask extends Task {
				public void run() {
					if (pressed && Core.input.mouse().dst(last) < MAX_OFF) {
						boolc.get(true);
					}
				}
			}
			final Task task = new LongPressTask();
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (event.stopped) return false;
				if (super.touchDown(event, x, y, pointer, button)) {
					last.set(Core.input.mouse());
					Timer.schedule(task, duration / 1000f);
					event.stop();
					return true;
				}
				return false;
			}
			public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
				super.touchUp(event, x, y, pointer, button);
				task.cancel();
			}
			public void clicked(InputEvent event, float x, float y) {
				// super.clicked(event, x, y);
				if (task.isScheduled()) {
					if (pressed) {
						boolc.get(false);
					}
				}
			}
		}
		elem.addListener(new LongPressListener());
		return elem;
	}

	public static <T extends Element> T
	longPress(T elem, final Boolc boolc) {
		return longPress(elem, DEF_LONGPRESS, boolc);
	}
	/**
	 * 长按事件
	 * @param <T>  the type parameter
	 * @param elem 被添加侦听器的元素
	 * @param duration 需要长按的事件（单位毫秒[ms]，600ms=0.6s）
	 * @param run 长按时调用
	 * @return the t
	 */
	public static <T extends Element> T
	longPress0(T elem, final long duration, final Runnable run) {
		return longPress(elem, duration, b -> {
			if (b) run.run();
		});
	}
	public static <T extends Element> T
	longPress0(T elem, final Runnable run) {
		return longPress0(elem, DEF_LONGPRESS, run);
	}

	/**
	 * 添加右键事件
	 * @param <T>  the type parameter
	 * @param elem 被添加侦听器的元素
	 * @param run 右键执行的代码
	 * @return the t
	 */
	public static <T extends Element> T
	rightClick(T elem, Runnable run) {
		class HClickListener extends ClickListener {
			HClickListener() {super(KeyCode.mouseRight);}
			public void clicked(InputEvent event, float x, float y) {
				if (event.stopped) return;
				run.run();
				event.stop();
			}
		}
		elem.addListener(new HClickListener());
		return elem;
	}

	/**
	 * <p>long press for {@link Vars#mobile moblie}</p>
	 * <p>r-click for desktop</p>
	 * @param <T>  the type parameter
	 * @param element the element
	 * @param run the run
	 * @return the t
	 */
	@SuppressWarnings("UnusedReturnValue")
	public static <T extends Element> T
	longPressOrRclick(T element, Consumer<T> run) {
		return mobile ? longPress0(element, () -> run.accept(element))
		 : rightClick(element, () -> run.accept(element));
	}

	/**
	 * Add show menu listener.
	 *
	 * @param elem 元素
	 * @param prov menu提供者
	 */
	public static void
	addShowMenuListenerp(Element elem, Prov<Seq<MenuList>> prov) {
		longPressOrRclick(elem, __ -> showMenuListDispose(prov));
	}
	/**
	 * Dispose after close.
	 *
	 * @param prov menu提供者
	 */
	public static void showMenuListDispose(Prov<Seq<MenuList>> prov) {
		Seq<MenuList> list = prov.get();
		showMenuList(list, () -> Pools.freeAll(list, false));
	}

	/**
	 * Add show menu listener.
	 *
	 * @param elem the elem
	 * @param list the list
	 */
	public static void
	addShowMenuListener(Element elem, MenuList... list) {
		longPressOrRclick(elem, __ -> {
			showMenuList(Seq.with(list));
		});
	}
	public static void
	addShowMenuListener(Element elem, Iterable<MenuList> list) {
		longPressOrRclick(elem, __ -> {
			showMenuList(list);
		});
	}
	public static void showMenuList(Iterable<MenuList> list) {
		showMenuList(list, null);
	}
	public static void showMenuList(Iterable<MenuList> list, Runnable hideMenu) {
		showSelectTableRB(Core.input.mouse().cpy(), (p, hide, ___) -> {
			showMeniList(list, hideMenu, p, hide);
		}, false);
	}
	public static SelectTable showMenuListFor(
	 Element elem,
	 int align, Prov<Seq<MenuList>> prov) {
		return showSelectTable(elem, (p, hide, ___) -> {
			Seq<MenuList> list = prov.get();
			showMeniList(list, () -> Pools.freeAll(list, false), p, hide);
		}, false, align);
	}
	/** TODO: 多个FoldedList有问题 */
	private static Cell<Table> showMeniList(Iterable<MenuList> list, Runnable hideMenu, Table p, Runnable hide) {
		return p.table(Styles.black6, main -> {
			for (var menu : list) {
				Cons<Button> cons = menu.cons;
				var cell = main.button(menu.getName(), menu.icon,
				 menu instanceof CheckboxList || menu instanceof FoldedList ? HopeStyles.flatToggleMenut : HopeStyles.flatt,
				 /** @see Cell#unset */
				 menu.icon != null ? 24 : Float.NEGATIVE_INFINITY/* unset */, () -> {}
				).minSize(DEFAULT_WIDTH, 42).marginLeft(5f).marginRight(5f);
				if (menu instanceof FoldedList foldedList) {
					Core.app.post(() -> {
						class MyRun implements Runnable {
							Cell<Table> newCell;
							BindCell    bcell;
							public void run() {
								if (newCell == null) {
									newCell = showMeniList(foldedList.getChildren(), hideMenu, p, hide);
									bcell = new BindCell(newCell);
								} else bcell.toggle();
							}
						}
						cell.get().clicked(new MyRun());
					});
					// newCell
				} else {
					cell.with(b -> b.clicked(catchRun(() -> {
						if (cons != null) cons.get(b);
						hide.run();
						if (hideMenu != null) hideMenu.run();
					}))).checked(menu instanceof CheckboxList l && l.checked);
				}
				cell.row();
				if (menu instanceof DisabledList) {
					cell.disabled(__ -> ((DisabledList) menu).disabled.get()).row();
				}
			}
		}).growY();
	}
	/**
	 * Menu `Copy ${key} As Js` constructor.
	 *
	 * @param prov 对象提供
	 * @return a menu.
	 */
	public static MenuList copyAsJSMenu(String key, Prov<Object> prov) {
		return MenuList.with(Icon.copySmall,
		 IntUI.buildStoreKey(key == null ? null : Core.bundle.get("jsfunc." + key, key)),
		 storeRun(prov));
	}
	public static MenuList copyAsJSMenu(String key, Runnable run) {
		return MenuList.with(Icon.copySmall,
		 IntUI.buildStoreKey(key == null ? null : Core.bundle.get("jsfunc." + key, key)),
		 run);
	}


	public static void addLabelButton(Table table, Prov<?> prov, Class<?> clazz) {
		addDetailsButton(table, prov, clazz);
		// addStoreButton(table, Core.bundle.get("jsfunc.value", "value"), prov);
	}
	public static void addDetailsButton(Table table, Prov<?> prov, Class<?> clazz) {
		/* table.button("@details", HopeStyles.flatBordert, () -> {
			Object o = prov.get();
			Core.app.post(() -> showInfo(o, o != null ? o.getClass() : clazz));
		}).size(96, 45); */
		table.button(Icon.infoCircleSmall, HopeStyles.clearNonei, 24, () -> {
			Object o = prov.get();
			Core.app.post(() -> JSFunc.showInfo(o, !clazz.isPrimitive() && o != null ? o.getClass() : clazz));
		}).size(32, 32);
	}

	public static void addStoreButton(Table table, String key, Prov<?> prov) {
		table.button(buildStoreKey(key),
			HopeStyles.flatBordert, () -> {}).padLeft(8f).size(180, 40)
		 .with(b -> {
			 b.clicked(() -> {
				 tester.put(b, prov.get());
			 });
		 });
	}
	public static String buildStoreKey(String key) {
		return key == null || key.isEmpty() ? Core.bundle.get("jsfunc.store_as_js_var2")
		 : Core.bundle.format("jsfunc.store_as_js_var", key);
	}

	/**
	 * Add watch button cell.
	 *
	 * @param buttons the buttons
	 * @param info the info
	 * @param value the value
	 * @return the cell
	 */
	public static Cell<?> addWatchButton(Table buttons, String info, MyProv<Object> value) {
		return buttons.button(Icon.eyeSmall, HopeStyles.clearNonei, () -> {}).with(b -> b.clicked(() -> {
			Sr((!WatchWindow.isMultiWatch() && Tools.getBound(topGroup.acquireShownWindows(), -2) instanceof WatchWindow w
			 ? w : JSFunc.watch()).watch(info, value).show())
			 .cons(WatchWindow::isEmpty, t -> t.setPosition(getAbsolutePos(b)));
		})).size(32);
	}


	/**
	 * Store run runnable.
	 *
	 * @param prov the prov
	 * @return the runnable
	 */
	public static Runnable storeRun(Prov<Object> prov) {
		return () -> tester.put(Core.input.mouse(), prov.get());
	}

	/**
	 * 在鼠标右下弹出一个小窗，自己设置内容
	 * @param vec2 用于定位弹窗的位置
	 * @param f (p, hide, text)                   p 是Table，你可以添加元素                   hide 是一个函数，调用就会关闭弹窗                   text 如果 @param 为 true ，则启用。用于返回用户在搜索框输入的文本
	 * @param searchable 可选，启用后会添加一个搜索框
	 * @return the table
	 */
	public static Table
	showSelectTableRB(Vec2 vec2, Cons3<Table, Runnable, String> f,
										boolean searchable) {
		Table t = new InsideTable();
		/* t.margin(6, 8, 6, 8); */
		Element hitter = new Hitter();
		Runnable hide = () -> {
			hitter.remove();
			t.actions(Actions.fadeOut(DEF_DURATION, Interp.fade),
			 Actions.remove());
		};
		hitter.clicked(hide);
		topGroup.addChild(hitter);
		topGroup.addChild(t);
		t.update(() -> {
			Tmp.v1.set(vec2);
			t.setPosition(Tmp.v1.x, Tmp.v1.y, Align.topLeft);
			if (t.getWidth() > Core.scene.getWidth()) {
				t.setWidth((float) graphics.getWidth());
			}

			if (t.getHeight() > Core.scene.getHeight()) {
				t.setHeight((float) graphics.getHeight());
			}

			t.keepInStage();
			t.invalidateHierarchy();
			t.pack();
		});
		t.actions(Actions.alpha(0f), Actions.fadeIn(DEF_DURATION, Interp.fade));
		Table p = new Table();
		p.top();
		if (searchable) {
			new Search((cont, text) -> {
				f.get(cont, hide, text);
			}).build(t, p);
		}

		f.get(p, hide, "");
		ScrollPane pane = new ScrollPane(p);
		t.top().add(p).pad(0.0f).top();
		pane.setScrollingDisabled(true, false);
		t.pack();
		return t;
	}

	/**
	 * 弹出一个小窗，自己设置内容
	 * @param <T>  the type parameter
	 * @param button 用于定位弹窗的位置
	 * @param f (p, hide, text)                   p 是Table，你可以添加元素                   hide 是一个函数，调用就会关闭弹窗                   text 如果 @param 为 true ，则启用。用于返回用户在搜索框输入的文本
	 * @param searchable 可选，启用后会添加一个搜索框
	 * @param align the align
	 * @return the select table
	 */
	public static <T extends Element> SelectTable
	showSelectTable(T button, Cons3<Table, Runnable, String> f,
									boolean searchable, int align) {
		if (button == null) throw new NullPointerException("button cannot be null");
		SelectTable t      = new SelectTable();
		Element     hitter = new Hitter();
		Runnable hide = () -> {
			hitter.remove();
			t.actions(Actions.fadeOut(DEF_DURATION, Interp.fade),
			 Actions.run(() -> t.fire(new VisibilityEvent(true))),
			 Actions.remove());
		};
		hitter.clicked(hide);
		topGroup.addChild(hitter);
		topGroup.addChild(t);
		t.update(() -> {
			if (button.parent != null && button.isDescendantOf(Core.scene.root)) {
				button.localToStageCoordinates(
				 Tmp.v1.set(button.getX(align), button.getY(align))
					.sub(button.x, button.y));
				if (Tmp.v1.y < graphics.getHeight() / 2f) {
					t.setPosition(Tmp.v1.x, Tmp.v1.y + button.getHeight() / 2f, align | Align.bottom);
				} else {
					t.setPosition(Tmp.v1.x, Tmp.v1.y - button.getHeight() / 2f, align | Align.top);
				}
				if (t.getWidth() > Core.scene.getWidth()) {
					t.setWidth((float) graphics.getWidth());
				}
				if (t.getHeight() >= Core.scene.getHeight()) {
					t.setHeight((float) graphics.getHeight());
					t.x += (t.x > graphics.getWidth() / 2f ? -1 : 1) * button.getWidth();
				}

				t.keepInStage();
				t.invalidateHierarchy();
				t.pack();
			} else {
				Core.app.post(hide);
			}
		});
		t.actions(Actions.alpha(0f), Actions.fadeIn(DEF_DURATION, Interp.fade));
		Table p = new Table();
		p.top();
		if (searchable) {
			new Search((cont, text) -> {
				f.get(cont, hide, text);
			}).build(t, p);
		}

		f.get(p, hide, "");
		ScrollPane pane = new ScrollPane(p, Styles.smallPane);
		t.top().add(pane).grow().pad(0f).top();
		pane.setScrollingDisabled(true, false);
		t.pack();
		return t;
	}

	public static <T extends Element> Table
	showSelectListTable(T button, Seq<String> list, Prov<String> holder,
											Cons<String> cons, int width, int height,
											boolean searchable, int align) {
		return showSelectListTable(button, list, holder, cons, s -> s, width, height, searchable, align);
	}
	public static <T extends Element, E extends Enum<E>> Table
	showSelectListEnumTable(T button, Seq<E> list, Prov<E> holder,
													Cons<E> cons, float width, float height,
													boolean searchable, int align) {
		return showSelectListTable(button, list, holder, cons,
		 Enum::name, width, height, searchable, align);
	}
	public static <BTN extends Element, V> Table
	showSelectListTable(
	 BTN button, Seq<V> list, Prov<V> holder,
	 Cons<V> cons, Func<V, String> stringify, float minWidth, float height,
	 boolean searchable, int align) {
		return showSelectTable(button, (p, hide, text) -> {
			p.clearChildren();

			Pattern pattern = PatternUtils.compileRegExpCatch(text);
			for (V item : list) {
				if (!PatternUtils.test(pattern, stringify.get(item))) continue;
				p.button(stringify.get(item), HopeStyles.cleart/*Styles.cleart*/, () -> {
					 cons.get(item);
					 hide.run();
				 }).minWidth(minWidth).growX()
				 .height(height).marginTop(6f).marginBottom(6f)
				 .disabled(Objects.equals(holder.get(), item)).row();
				p.image().color(Tmp.c1.set(JSFunc.c_underline)).growX().row();
			}

		}, searchable, align);
	}

	/**
	 * 弹出一个可以选择内容的窗口（类似物品液体源的选择）
	 * （需要提供图标）
	 * @param <T>  the type parameter
	 * @param <T1>  the type parameter
	 * @param button the button
	 * @param items 用于展示可选的内容
	 * @param icons 可选内容的图标
	 * @param holder 选中的内容，null就没有选中任何
	 * @param cons 选中内容就会调用
	 * @param size 每个内容的元素大小
	 * @param imageSize 每个内容的图标大小
	 * @param cols 一行的元素数量
	 * @param searchable the searchable
	 * @return the table
	 */
	public static <T extends Button, T1> Table
	showSelectImageTableWithIcons(T button, Seq<T1> items,
																Seq<? extends Drawable> icons,
																Prov<T1> holder, Cons<T1> cons, float size,
																float imageSize, int cols,
																boolean searchable) {
		return showSelectTable(button, getCons3(items, icons, holder, cons, size, imageSize, cols), searchable, Align.center);
	}

	public static <T1> Table
	showSelectImageTableWithIcons(Vec2 vec2, Seq<T1> items,
																Seq<? extends Drawable> icons,
																Prov<T1> holder, Cons<T1> cons, float size,
																float imageSize, int cols,
																boolean searchable) {
		return showSelectTable(vec2, getCons3(items, icons, holder, cons, size, imageSize, cols), searchable);
	}
	private static <T1> Cons3<Table, Runnable, String> getCons3(
	 Seq<T1> items, Seq<? extends Drawable> icons,
	 Prov<T1> holder, Cons<T1> cons, float size, float imageSize, int cols) {
		return (p, hide, text) -> {
			p.clearChildren();
			p.left();
			ButtonGroup<ImageButton> group = new ButtonGroup<>();
			group.setMinCheckCount(0);
			p.defaults().size(size);
			Pattern pattern;
			try {
				pattern = PatternUtils.compileRegExp(text);
			} catch (Exception ex) {return;}

			for (int c = 0, i = 0; i < items.size; ++i) {
				T1 item = items.get(i);
				if (PatternUtils.testContent(text, pattern, item)) continue;

				ImageButton btn = Hover.getImageButton(cons, size, imageSize, p, hide, item, icons.get(i));
				btn.update(() -> {
					btn.setChecked(holder.get() == item);
				});
				if (++c % cols == 0) {
					p.row();
				}
			}
		};
	}

	public static SelectTable
	showSelectTable(Vec2 vec2, Cons3<Table, Runnable, String> f,
									boolean searchable) {
		SelectTable t      = new SelectTable();
		Element     hitter = new Hitter();
		Runnable hide = () -> {
			hitter.remove();
			t.actions(Actions.fadeOut(DEF_DURATION, Interp.fade), Actions.remove());
		};
		hitter.clicked(hide);
		topGroup.addChild(hitter);
		topGroup.addChild(t);
		t.update(() -> {
			t.setPosition(vec2.x, vec2.y, 1);
			if (t.getWidth() > Core.scene.getWidth()) {
				t.setWidth((float) graphics.getWidth());
			}

			if (t.getHeight() > Core.scene.getHeight()) {
				t.setHeight((float) graphics.getHeight());
			}
			// if (t.y < 0) t.x += (t.x > graphics.getWidth() / 2f ? -1 : 1) * t.getWidth();

			t.keepInStage();
			t.invalidateHierarchy();
			t.pack();
		});
		t.actions(Actions.alpha(0f), Actions.fadeIn(DEF_DURATION, Interp.fade));
		Table p = new Table();
		p.top();
		if (searchable) {
			new Search((cont, text) -> {
				f.get(cont, hide, text);
			}).build(t, p);
		}

		f.get(p, hide, "");
		ScrollPane pane = new ScrollPane(p, Styles.smallPane);
		t.top().add(pane).pad(0).top();
		pane.setScrollingDisabled(true, false);
		t.pack();
		return t;
	}


	/**
	 * 弹出一个可以选择内容的窗口（无需你提供图标，需要 <i>{@link UnlockableContent}</i>）
	 * @param <T1>  the type parameter
	 * @param vec2 the vec 2
	 * @param items the items
	 * @param holder the holder
	 * @param cons the cons
	 * @param size the size
	 * @param imageSize the image size
	 * @param cols the cols
	 * @param searchable the searchable
	 * @return the table
	 */
	public static <T1 extends UnlockableContent> Table
	showSelectImageTable(Vec2 vec2, Seq<T1> items,
											 Prov<T1> holder,
											 Cons<T1> cons, float size,
											 float imageSize, int cols,
											 boolean searchable) {
		return showSelectImageTableWithFunc(vec2, items, holder, cons, size, imageSize, cols,
		 u -> new TextureRegionDrawable(u.uiIcon), searchable);
	}
	/**
	 * 弹出一个可以选择内容的窗口（需你提供{@link Func 图标构造器}）
	 * @param <T1>  the type parameter
	 * @param vec2 the vec 2
	 * @param items the items
	 * @param holder the holder
	 * @param cons the cons
	 * @param size the size
	 * @param imageSize the image size
	 * @param cols the cols
	 * @param func the func
	 * @param searchable the searchable
	 * @return the table
	 */
	public static <T1> Table
	showSelectImageTableWithFunc(Vec2 vec2, Seq<T1> items, Prov<T1> holder,
															 Cons<T1> cons, float size, float imageSize,
															 int cols, Func<T1, Drawable> func,
															 boolean searchable) {
		Seq<Drawable> icons = new Seq<>(items.size);
		items.each(item -> {
			icons.add(func.get(item));
		});
		return showSelectImageTableWithIcons(vec2, items, icons, holder, cons, size, imageSize, cols, searchable);
	}
	public static <T extends Button, T1> Table
	showSelectImageTableWithFunc(T button, Seq<T1> items, Prov<T1> holder,
															 Cons<T1> cons, float size, float imageSize,
															 int cols, Func<T1, Drawable> func,
															 boolean searchable) {
		Seq<Drawable> icons = new Seq<>(items.size);
		items.each(item -> {
			icons.add(func.get(item));
		});
		return showSelectImageTableWithIcons(button, items, icons, holder, cons, size, imageSize, cols, searchable);
	}

	/**
	 * Window弹窗错误
	 * @param t the t
	 * @return the window
	 */
	public static Window showException(Throwable t) {
		return showException("", t);
	}

	static ExceptionPopup lastException;
	public static Window showException(String text, Throwable exc) {
		ui.loadfrag.hide();
		return (lastException != null && lastException.isShown() ? lastException : new ExceptionPopup(exc, text)).setPosition(Core.input.mouse());
	}

	public static Window showInfoFade(String info) {
		return showInfoFade(info, Core.input.mouse());
	}

	public static Window showInfoFade(String info, Vec2 pos) {
		return showInfoFade(info, pos, Align.center);
	}
	public static Window showInfoFade(String info, Vec2 pos, int align) {
		return new InfoFadePopup("Info", 80, 64) {{
			cont.add(info);
			setPosition(pos, align);
			// 1.2s
			Time.runTask(60 * 1.2f, this::hide);
		}}.show();
	}

	public static ConfirmWindow showConfirm(String text, Runnable confirmed) {
		return showConfirm("@confirm", text, null, confirmed);
	}
	public static ConfirmWindow showConfirm(String title, String text, Runnable confirmed) {
		return showConfirm(title, text, null, confirmed);
	}
	public static ConfirmWindow showConfirm(String title, String text, Boolp hide, Runnable confirmed) {
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
		window.keyDown(KeyCode.enter, () -> {
			window.hide();
			confirmed.run();
		});
		window.keyDown(KeyCode.escape, window::hide);
		window.keyDown(KeyCode.back, window::hide);
		window.setPosition(Core.input.mouse());
		return window;
	}


	public static void colorBlock(Cell<?> cell, Color color, boolean needDouble) {
		colorBlock(cell, color, null, needDouble);
	}
	/**
	 * <p>为{@link Cell cell}添加一个{@link Color color（颜色）}块</p>
	 * {@linkplain #colorBlock(Cell, Color, Cons, boolean)
	 * colorBlock(
	 * cell,
	 * color,
	 * callback,
	 * needDclick: boolean = true
	 * )}*
	 * @param cell the cell
	 * @param color the color
	 * @param callback the callback
	 * @see #colorBlock(Cell cell, Color color, Cons callback, boolean needDclick) #colorBlock(Cell cell, Color color, Cons callback, boolean needDclick)
	 */
	public static void colorBlock(Cell<?> cell, Color color, Cons<Color> callback) {
		colorBlock(cell, color, callback, true);
	}

	/**
	 * <p>为{@link Cell cell}添加一个{@link Color color（颜色）}块</p>
	 * @param cell 被修改成颜色块的cell
	 * @param color 初始化颜色
	 * @param callback 回调函数，形参为修改后的{@link Color color}
	 * @param needDclick 触发修改事件，是否需要双击（{@code false}为点击）
	 */
	public static void colorBlock(Cell<?> cell, Color color, Cons<Color> callback, boolean needDclick) {
		BorderImage image = new ColorContainer(color);
		cell.setElement(image).size(42f);
		Runnable runnable = () -> {
			IntUI.picker.show(color, c1 -> {
				color.set(c1);
				if (callback != null) callback.get(c1);
			});
			Core.app.post(() -> IntUI.picker.setPosition(getAbsolutePos(image), Align.left | Align.center));
		};
		IntUI.doubleClick(image, needDclick ? null : runnable, needDclick ? runnable : null);
	}


	public static class ColorContainer extends BorderImage {
		private Color colorValue;
		/**
		 * Instantiates a new Color container.
		 *
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
		 *
		 * @param color the color
		 */
		public void setColorValue(Color color) {
			colorValue = color;
		}
	}



	public static void addCheck(Cell<? extends ImageButton> cell, Boolp boolp,
															String valid, String unvalid) {
		cell.get().addListener(new IntUI.Tooltip(
		 t -> t.background(Tex.pane).label(() -> boolp.get() ? valid : unvalid)
		));
		cell.update(b -> b.getStyle().imageUpColor = boolp.get() ? Color.white : Color.gray);
	}

	public static String tips(String key) {
		return Core.bundle.format("mod-tools.tips", Core.bundle.get("mod-tools.tips." + key));
	}


	public static final
	Color DEF_MASK_COLOR = Color.black.cpy().a(0.5f),
	DEF_FOCUS_COLOR = Color.blue.cpy().a(0.4f);

	/**
	 * 聚焦一个元素
	 * @param element 要聚焦的元素
	 * @param boolp {@link Boolp#get()}的返回值如果为{@code false}则移除聚焦
	 */
	public static void focusOnElement(Element element, Boolp boolp) {
		topGroup.focusOnElement(new MyFocusTask(element, boolp));
	}


	public static class Tooltip extends arc.scene.ui.Tooltip {

		/**
		 * Instantiates a new Tooltip.
		 *
		 * @param contents the contents
		 */
		public Tooltip(Cons<Table> contents) {
			super(t -> {});
			allowMobile = true;
			/* 异步执行时，字体会缺失  */
			show = () -> {
				Table table = container;
				if (table.getChildren().isEmpty()) contents.get(table);
				table.pack();
				topGroup.addChild(table);
			};
		}
		/**
		 * Instantiates a new Tooltip.
		 *
		 * @param contents the contents
		 * @param show the show
		 */
		public Tooltip(Cons<Table> contents, Runnable show) {
			super(contents, show);
		}
		/**
		 * Instantiates a new Tooltip.
		 *
		 * @param contents the contents
		 * @param manager the manager
		 */
		public Tooltip(Cons<Table> contents, Tooltips manager) {
			super(contents, manager);
		}
		public void show(Element element, float x, float y) {
			super.show(element, x, y);
			if (mobile) Time.runTask(60 * 1.2f, this::hide);
		}
		/** 禁用原本的mobile自动隐藏 */
		public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {}

		static {
			Tooltips.getInstance().textProvider = text -> new Tooltip(t -> t.background(Styles.black6).margin(4f).add(text));
		}
	}

	// ======-----弹窗------======
	public interface PopupWindow extends INotice {}
	/**
	 * The interface Menu.
	 */
	public interface IMenu extends IDisposable {
	}
	/**
	 * The interface Notice.
	 */
	public interface INotice extends IDisposable {}

	public static class InfoFadePopup extends NoTopWindow implements DelayDisposable {
		/**
		 * Instantiates a new Info fade popup.
		 *
		 * @param title the title
		 * @param width the width
		 * @param height the height
		 */
		public InfoFadePopup(String title, float width, float height) {
			super(title, width, height);
			removeCaptureListener(sclListener);
		}
	}
	private static class ExceptionPopup extends Window implements PopupWindow {
		/**
		 * Instantiates a new Exception popup.
		 *
		 * @param exc the exc
		 * @param text the text
		 */
		public ExceptionPopup(Throwable exc, String text) {
			super("", 0, 200, false);
			String message = Strings.getFinalMessage(exc);

			cont.margin(15);
			cont.add("@error.title").colspan(2);
			cont.row();
			cont.image().width(300f).pad(2).colspan(2).height(4f).color(Color.scarlet);
			cont.row();
			cont.add(text == null ? "" : (text.startsWith("@") ? Core.bundle.get(text.substring(1)) : text) + (message == null ? "" : "\n[lightgray](" + message + ")"))
			 .colspan(2).wrap().growX().center()
			 .get().setAlignment(Align.center);
			cont.row();

			Collapser col = new Collapser(base -> base.pane(t -> t.margin(14f).add(Strings.neatError(exc)).color(Color.lightGray).left()), true);

			cont.button("@details", Styles.togglet, col::toggle).size(180f, 50f).checked(b -> !col.isCollapsed()).growX().right();
			col.setDuration(0.2f);
			cont.button("@ok", this::hide).size(110, 50).growX().left();
			cont.row();
			col.setCollapsed(false, false);
			cont.add(col).colspan(2).pad(2);
			//            closeOnBack();
		}
	}
	public static class ConfirmWindow extends Window implements IDisposable, PopupWindow {
		/**
		 * Instantiates a new Confirm window.
		 *
		 * @param title the title
		 * @param minWidth the min width
		 * @param minHeight the min height
		 * @param full the full
		 * @param noButtons the no buttons
		 */
		public ConfirmWindow(String title, float minWidth, float minHeight, boolean full, boolean noButtons) {
			super(title, minWidth, minHeight, full, noButtons);
		}

		/**
		 * Sets center.
		 *
		 * @param vec2 the vec 2
		 */
		public void setCenter(Vec2 vec2) {
			setPosition(vec2.x - getPrefWidth() / 2f, vec2.y - getPrefHeight() / 2f);
		}
	}
	private static class AutoFitTable extends Table implements PopupWindow {
		/**
		 * Instantiates a new Auto fit table.
		 */
		public AutoFitTable() {super(Tex.pane);}
		public float getPrefHeight() {
			return Math.min(super.getPrefHeight(), (float) graphics.getHeight());
		}

		public float getPrefWidth() {
			return Math.min(super.getPrefWidth(), (float) graphics.getWidth());
		}
	}
	public static class InsideTable extends Table implements IMenu {
		/**
		 * Instantiates a new Inside table.
		 */
		public InsideTable() {
		}
		/**
		 * Instantiates a new Inside table.
		 *
		 * @param background the background
		 */
		public InsideTable(Drawable background) {
			super(background);
		}
		/**
		 * Instantiates a new Inside table.
		 *
		 * @param background the background
		 * @param cons the cons
		 */
		public InsideTable(Drawable background, Cons<Table> cons) {
			super(background, cons);
		}
		public InsideTable(Cons<Table> cons) {
			super(cons);
		}
		public float getPrefHeight() {
			return Math.min(super.getPrefHeight(), (float) graphics.getHeight());
		}

		public float getPrefWidth() {
			return Math.min(super.getPrefWidth(), (float) graphics.getWidth());
		}
	}

	public static class SelectTable extends AutoFitTable implements IMenu {
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
	}

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
			Draw.blit(bufferCaptureAll(getAbsolutePos(elem), elem), baseShader);
		}
		public void elemDraw() {}
		public void endDraw() {
			super.endDraw();
			drawFocus(elem);
		}
	}
}
