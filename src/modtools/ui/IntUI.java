
package modtools.ui;

import arc.Core;
import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.input.KeyCode;
import arc.math.*;
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
import arc.util.pooling.Pool.Poolable;
import arc.util.pooling.Pools;
import mindustry.Vars;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.*;
import mindustry.ui.*;
import modtools.ui.TopGroup.FocusTask;
import modtools.ui.components.Window;
import modtools.ui.components.Window.*;
import modtools.utils.*;
import modtools.utils.ui.search.*;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static arc.Core.graphics;
import static mindustry.Vars.*;
import static modtools.graphics.MyShaders.baseShader;
import static modtools.ui.Contents.tester;
import static modtools.ui.effect.ScreenSampler.bufferCaptureAll;
import static modtools.utils.ElementUtils.getAbsPos;

public class IntUI {
	public static final TextureRegionDrawable whiteui = (TextureRegionDrawable) Tex.whiteui;

	public static final Frag     frag          = new Frag();
	public static final TopGroup topGroup      = new TopGroup();
	public static       float    default_width = 150;

	public static void load() {
		if (frag.getChildren().isEmpty()) {
			frag.load();
		} else {
			topGroup.addChild(frag);
		}
	}

	/** 默认的动效时间（单位秒） */
	public static final float DEF_DURATION = 0.2f;

	static final Vec2 last = new Vec2();
	/**
	 * <p>创建一个双击事件</p>
	 * <p color="gray">我还做了位置偏移计算，防止误触</p>
	 * @param elem    被添加侦听器的元素
	 * @param click   单击事件
	 * @param d_click 双击事件
	 */
	public static <T extends Element> T
	doubleClick(T elem, Runnable click, Runnable d_click) {
		if (click == null && d_click == null) return elem;
		class DoubleClick extends ClickListener {
			final Task clickTask = new Task() {
				public void run() {
					if (click != null) click.run();
				}
			};
			public void clicked(InputEvent event, float x, float y) {
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
				if (mouse.dst(last) < 35f) d_click.run();
			}
		}
		elem.addListener(new DoubleClick());
		return elem;
	}


	/**
	 * 长按事件
	 * @param elem     被添加侦听器的元素
	 * @param duration 需要长按的事件（单位毫秒[ms]，600ms=0.6s）
	 * @param boolc    {@link Boolc#get(boolean b)}形参{@code b}为是否长按
	 */
	public static <T extends Element> T
	longPress(T elem, final long duration, final Boolc boolc) {
		elem.addListener(new ClickListener() {
			final Task task = new Task() {
				public void run() {
					if (pressed) {
						boolc.get(true);
					}
				}
			};
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (super.touchDown(event, x, y, pointer, button)) {
					Timer.schedule(task, duration / 1000f);
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
					if (pressed) boolc.get(false);
				}
			}
		});
		return elem;
	}

	/**
	 * 添加右键事件
	 * @param elem 被添加侦听器的元素
	 * @param run  右键执行的代码
	 */
	public static <T extends Element> T
	rightClick(T elem, Runnable run) {
		elem.addListener(new ClickListener(KeyCode.mouseRight) {
			public void clicked(InputEvent event, float x, float y) {
				run.run();
			}
		});
		return elem;
	}

	/**
	 * <p>long press for {@link Vars#mobile moblie}</p>
	 * <p>r-click for desktop</p>
	 */
	public static <T extends Element> T
	longPressOrRclick(T element, Consumer<T> run) {
		return mobile ? longPress(element, 600, b -> {
			if (b) run.accept(element);
		}) : rightClick(element, () -> run.accept(element));
	}

	public static void
	addShowMenuListener(Element elem, Prov<Seq<MenuList>> prov) {
		longPressOrRclick(elem, __ -> showMenuListDispose(prov));
	}
	public static void showMenuListDispose(Prov<Seq<MenuList>> prov) {
		Seq<MenuList> list = prov.get();
		showMenuList(list, () -> Pools.freeAll(list, false));
	}

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
	/** TODO: 多个FoldedList有问题 */
	private static Cell<Table> showMeniList(Iterable<MenuList> list, Runnable hideMenu, Table p, Runnable hide) {
		return p.table(main -> {
			for (var menu : list) {
				Cons<Button> cons = menu.cons;
				var cell = main.button(menu.getName(), menu.icon,
				 menu instanceof CheckboxList || menu instanceof FoldedList ? Styles.flatTogglet : Styles.flatt,
				 /** @see Cell#unset */
				 menu.icon != null ? 24 : Float.NEGATIVE_INFINITY/* unset */, () -> {}
				).minSize(default_width, 42).marginLeft(5f).marginRight(5f);
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
					cell.with(b -> b.clicked(() -> {
						if (cons != null) cons.get(b);
						hide.run();
						if (hideMenu != null) hideMenu.run();
					})).checked(menu instanceof CheckboxList l && l.checked);
				}
				cell.row();
				if (menu instanceof DisabledList) {
					cell.disabled(__ -> ((DisabledList) menu).disabled.get()).row();
				}
			}
		}).growY();
	}
	public static MenuList copyAsJSMenu(String key, Prov<Object> prov) {
		return MenuList.with(Icon.copySmall,
		 JSFunc.buildStoreKey(key == null ? null : Core.bundle.get("jsfunc." + key, key)),
		 storeRun(prov));
	}
	public static MenuList copyAsJSMenu(String key, Runnable run) {
		return MenuList.with(Icon.copySmall,
		 JSFunc.buildStoreKey(key == null ? null : Core.bundle.get("jsfunc." + key, key)),
		 run);
	}

	public static Runnable storeRun(Prov<Object> prov) {
		return () -> tester.put(Core.input.mouse(), prov.get());
	}

	/**
	 * 在鼠标右下弹出一个小窗，自己设置内容
	 * @param vec2       用于定位弹窗的位置
	 * @param f          (p, hide, text)
	 *                   p 是Table，你可以添加元素
	 *                   hide 是一个函数，调用就会关闭弹窗
	 *                   text 如果 @param 为 true ，则启用。用于返回用户在搜索框输入的文本
	 * @param searchable 可选，启用后会添加一个搜索框
	 */
	public static Table
	showSelectTableRB(Vec2 vec2, Cons3<Table, Runnable, String> f,
										boolean searchable) {
		Table t = new InsideTable();
		/* t.margin(6, 8, 6, 8); */
		class Hitter extends Element /* implements BackInterface */ {}
		Element hitter = new Hitter();
		Runnable hide = () -> {
			hitter.remove();
			t.actions(Actions.fadeOut(DEF_DURATION, Interp.fade), Actions.remove());
		};
		hitter.clicked(hide);
		hitter.fillParent = true;
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
	 * @param button     用于定位弹窗的位置
	 * @param f          (p, hide, text)
	 *                   p 是Table，你可以添加元素
	 *                   hide 是一个函数，调用就会关闭弹窗
	 *                   text 如果 @param 为 true ，则启用。用于返回用户在搜索框输入的文本
	 * @param searchable 可选，启用后会添加一个搜索框
	 */
	public static <T extends Element> Table
	showSelectTable(T button, Cons3<Table, Runnable, String> f,
									boolean searchable) {
		if (button == null) throw new NullPointerException("button cannot be null");
		Table   t      = new AutoFitTable();
		Element hitter = new Element();
		Runnable hide = () -> {
			hitter.remove();
			t.actions(Actions.fadeOut(0.3f, Interp.fade), Actions.remove());
		};
		hitter.clicked(hide);
		hitter.fillParent = true;
		topGroup.addChild(hitter);
		topGroup.addChild(t);
		t.update(() -> {
			if (button.parent != null && button.isDescendantOf(Core.scene.root)) {
				button.localToStageCoordinates(Tmp.v1.set(button.getWidth() / 2f, button.getHeight() / 2f));
				if (Tmp.v1.y < graphics.getHeight() / 2f) {
					t.setPosition(Tmp.v1.x, Tmp.v1.y + button.getHeight() / 2f, Align.center | Align.bottom);
				} else {
					t.setPosition(Tmp.v1.x, Tmp.v1.y - button.getHeight() / 2f, Align.center | Align.top);
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
		t.actions(Actions.alpha(0.0f), Actions.fadeIn(0.3f, Interp.fade));
		Table p = new Table();
		p.top();
		if (searchable) {
			new Search((cont, text) -> {
				f.get(cont, hide, text);
			}).build(t, p);
		}

		f.get(p, hide, "");
		ScrollPane pane = new ScrollPane(p);
		t.top().add(pane).pad(0.0f).top();
		pane.setScrollingDisabled(true, false);
		t.pack();
		return t;
	}

	public static <T extends Button> Table
	showSelectListTable(T button, Seq<String> list, Prov<String> holder,
											Cons<String> cons, int width, int height,
											Boolean searchable) {
		return showSelectListTable(button, list, holder, cons, s -> s, width, height, searchable);
	}
	public static <BTN extends Button, V> Table
	showSelectListTable(
	 BTN button, Seq<V> list, Prov<V> holder,
	 Cons<V> cons, Func<V, String> stringify, int width, int height,
	 Boolean searchable) {
		return showSelectTable(button, (p, hide, text) -> {
			p.clearChildren();

			for (V item : list) {
				p.button(stringify.get(item), IntStyles.flatt/*Styles.cleart*/, () -> {
					cons.get(item);
					hide.run();
				}).size((float) width, (float) height).disabled(Objects.equals(holder.get(), item)).row();
			}

		}, searchable);
	}

	/**
	 * 弹出一个可以选择内容的窗口（类似物品液体源的选择）
	 * （需要提供图标）
	 * @param items     用于展示可选的内容
	 * @param icons     可选内容的图标
	 * @param holder    选中的内容，null就没有选中任何
	 * @param size      每个内容的元素大小
	 * @param imageSize 每个内容的图标大小
	 * @param cons      选中内容就会调用
	 * @param cols      一行的元素数量
	 */
	public static <T extends Button, T1> Table
	showSelectImageTableWithIcons(T button, Seq<T1> items,
																Seq<? extends Drawable> icons,
																Prov<T1> holder, Cons<T1> cons, float size,
																float imageSize, int cols,
																boolean searchable) {
		return showSelectTable(button, getCons3(items, icons, holder, cons, size, imageSize, cols), searchable);
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
				if (isMatched(text, pattern, item)) continue;

				ImageButton btn = getImageButton(cons, size, imageSize, p, hide, item, icons.get(i));
				btn.update(() -> {
					btn.setChecked(holder.get() == item);
				});
				if (++c % cols == 0) {
					p.row();
				}
			}
		};
	}
	public static <T1> ImageButton getImageButton(Cons<T1> cons, float size, float imageSize, Table p, Runnable hide,
																								T1 item, Drawable icon) {
		ImageButton btn = p.button(Tex.whiteui, Styles.clearTogglei, imageSize, () -> {}).size(size).get();
		longPress(btn, 500, b -> {
			if (b) return;
			cons.get(item);
			hide.run();
		});

		if (!mobile) addHover(imageSize, btn);
		// if (!mobile) {
		btn.addListener(new Tooltip(t -> {
			t.background(Tex.pane).add(item instanceof UnlockableContent u ? u.localizedName : "" + item)
			 .right().bottom();
		}));
		// }
		btn.getStyle().imageUp = icon;
		return btn;
	}
	private static void addHover(float imageSize, ImageButton btn) {
		var task = new Task() {
			boolean reverse = false;
			float a = 0;
			public void run() {
				a += (reverse ? -1 : 1) * 0.1f;
				btn.resizeImage(imageSize + Interp.pow2.apply(0, 5, Mathf.clamp(a)));
				btn.invalidate();
				if (reverse ? a <= 0 : a >= 1) {
					cancel();
				}
			}
		};
		btn.hovered(() -> {
			task.reverse = false;
			if (!task.isScheduled()) Timer.schedule(task, 0, 0.02f, -1);
		});
		btn.exited(() -> {
			task.reverse = true;
			if (!task.isScheduled()) Timer.schedule(task, 0, 0.02f, -1);
		});
	}
	private static <T1> boolean isMatched(String text, Pattern pattern, T1 item) {
		if (text == null || text.isEmpty()) return false;
		if (pattern == null) return true;
		if (item instanceof UnlockableContent unlock) {
			return !pattern.matcher(unlock.name).find() && !pattern.matcher(unlock.localizedName).find();
		}
		return !pattern.matcher("" + item).find();
	}
	public static Table
	showSelectTable(Vec2 vec2, Cons3<Table, Runnable, String> f,
									boolean searchable) {
		Table   t      = new AutoFitTable();
		Element hitter = new Element();
		Runnable hide = () -> {
			hitter.remove();
			t.actions(Actions.fadeOut(0.3f, Interp.fade), Actions.remove());
		};
		hitter.clicked(hide);
		hitter.fillParent = true;
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
		t.actions(Actions.alpha(0.0f), Actions.fadeIn(0.3f, Interp.fade));
		Table p = new Table();
		p.top();
		if (searchable) {
			new Search((cont, text) -> {
				f.get(cont, hide, text);
			}).build(t, p);
		}

		f.get(p, hide, "");
		ScrollPane pane = new ScrollPane(p);
		t.top().add(pane).pad(0.0f).top();
		pane.setScrollingDisabled(true, false);
		t.pack();
		return t;
	}


	/**
	 * 弹出一个可以选择内容的窗口（无需你提供图标，需要 <i>{@link UnlockableContent}</i>）
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
		return showSelectImageTableWithIcons(vec2, items, icons, holder, cons, size, (float) imageSize, cols, searchable);
	}
	/**
	 * {@literal }
	 * 弹出一个可以选择内容的窗口（需你提供图标构造器）
	 */
	public static <T extends Button, T1> Table
	showSelectImageTableWithFunc(T button, Seq<T1> items, Prov<T1> holder,
															 Cons<T1> cons, float size, float imageSize,
															 int cols, Func<T1, Drawable> func,
															 boolean searchable) {
		Seq<Drawable> icons = new Seq<>(items.size);
		items.each(item -> {
			icons.add(func.get(item));
		});
		return showSelectImageTableWithIcons(button, items, icons, holder, cons, size, (float) imageSize, cols, searchable);
	}

	/**
	 * Window弹窗错误
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
		return new InfoFadePopup("info", 100, 64) {{
			cont.add(info);
			setPosition(pos, align);
			// 1.2s
			Time.runTask(60 * 1.2f, this::hide);
		}};
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
	 * )}
	 * @see #colorBlock(Cell cell, Color color, Cons callback, boolean needDclick)
	 */
	public static void colorBlock(Cell<?> cell, Color color, Cons<Color> callback) {
		colorBlock(cell, color, callback, true);
	}
	/**
	 * <p>为{@link Cell cell}添加一个{@link Color color（颜色）}块</p>
	 * @param cell       被修改成颜色块的cell
	 * @param color      初始化颜色
	 * @param callback   回调函数，形参为修改后的{@link Color color}
	 * @param needDclick 触发修改事件，是否需要双击（{@code false}为点击）
	 */
	public static void colorBlock(Cell<?> cell, Color color, Cons<Color> callback, boolean needDclick) {
		BorderImage image = new BorderImage(Core.atlas.white(), 2f) {
			public void draw() {
				float alpha = Draw.getColor().a;
				Draw.alpha(1f);
				Tex.alphaBg.draw(x, y, width, height);
				Draw.alpha(alpha);
				super.draw();
			}
		};
		Color inv = new Color();
		cell.setElement(image).color(color).size(42f)
		 .update(el -> {
			 el.color.set(color);
			 image.border(inv.set(color).inv());
		 });
		Runnable runnable = () -> {
			ui.picker.show(color, c1 -> {
				color.set(c1);
				if (callback != null) callback.get(c1);
			});
			topGroup.addChild(ui.picker);
		};
		IntUI.doubleClick(image, needDclick ? null : runnable, needDclick ? runnable : null);
	}


	public static void addCheck(Cell<? extends ImageButton> cell, Boolp boolp,
															String valid, String unvalid) {
		cell.get().addListener(new IntUI.Tooltip(t -> t.background(Tex.pane)
		 .label(() -> boolp.get() ? valid : unvalid)));
		cell.update(b -> b.getStyle().imageUpColor = boolp.get() ? Color.white : Color.gray);
	}

	public static String tips(String key) {
		return Core.bundle.format("mod-tools.tips", Core.bundle.get("mod-tools.tips." + key));
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
		topGroup.focusOnElement(new FocusTask(element, DEF_MASK_COLOR, Color.clear) {
			public void backDraw() {
				super.backDraw();
				if (!boolp.get()) topGroup.removeFocusElement(this);
			}
			public void drawFocus(Element elem) {
				super.drawFocus(elem);
				Draw.blit(bufferCaptureAll(getAbsPos(elem), elem), baseShader);
			}
			public void elemDraw() {}
			public void endDraw() {
				super.endDraw();
				drawFocus(elem);
			}
		});
	}


	/* 整数倒计时 */
	public static void countdown(int times, Intc cons) {
		Timer.schedule(new Task() {
			int i = times;
			public void run() {
				cons.get(i);
				i--;
			}
		}, 0, 1, times);
	}

	public static class MenuList {
		public static int          max = 20;
		public        Drawable     icon;
		public        String       name;
		public        Prov<String> provider;
		public        Cons<Button> cons;
		public static MenuList with(Drawable icon, String name, Runnable run) {
			MenuList list = Pools.get(MenuList.class, MenuList::new, max).obtain();
			list.icon = icon;
			list.name = name;
			list.provider = null;
			list.cons = __ -> run.run();
			return list;
		}
		public static MenuList with(Drawable icon, String name, Cons<Button> cons) {
			MenuList list = Pools.get(MenuList.class, MenuList::new, max).obtain();
			list.icon = icon;
			list.name = name;
			list.provider = null;
			list.cons = cons;
			return list;
		}
		public static MenuList with(Drawable icon, Prov<String> provider, Runnable run) {
			MenuList list = Pools.get(MenuList.class, MenuList::new, max).obtain();
			list.icon = icon;
			list.name = null;
			list.provider = provider;
			list.cons = __ -> run.run();
			return list;
		}
		public static MenuList with(Drawable icon, String name, Prov prov) {
			return with(icon, name, () -> {
				tester.put(prov.get());
			});
		}

		private MenuList() {}

		public String getName() {
			return provider != null ? provider.get() : name;
		}
	}
	public static class ConfirmList extends MenuList {
		public static MenuList with(Drawable icon, String name, String text, Runnable run) {
			MenuList list = MenuList.with(icon, name, run);
			list.cons = __ -> IntUI.showConfirm(text, run);
			return list;
		}
	}
	public static class CheckboxList extends MenuList {
		public boolean checked;
		public static CheckboxList withc(Drawable icon, String name, boolean checked, Runnable run) {
			CheckboxList list = Pools.get(CheckboxList.class, CheckboxList::new, max).obtain();
			list.icon = icon;
			list.name = name;
			list.checked = checked;
			// Log.info("0) check: @, list.checked: @", checked, list.checked);
			list.cons = __ -> run.run();
			return list;
		}
	}
	public static class DisabledList extends MenuList {
		public Boolp disabled;
		public static DisabledList withd(Drawable icon, String name, Boolp disabled, Runnable run) {
			DisabledList list = Pools.get(DisabledList.class, DisabledList::new, max).obtain();
			list.icon = icon;
			list.name = name;
			list.disabled = disabled;
			// Log.info("0) check: @, list.checked: @", checked, list.checked);
			list.cons = __ -> run.run();
			return list;
		}
	}
	public static class FoldedList extends MenuList implements Poolable {
		Prov<Seq<MenuList>> childrenGetter;
		Seq<MenuList>       children;
		public static FoldedList withf(Drawable icon, String name, Prov<Seq<MenuList>> children) {
			FoldedList list = Pools.get(FoldedList.class, FoldedList::new, max).obtain();
			list.icon = icon;
			list.name = name;
			list.children = null;
			list.childrenGetter = children;
			// Log.info("0) check: @, list.checked: @", checked, list.checked);
			list.cons = null;
			return list;
		}
		public Seq<MenuList> getChildren() {
			if (children == null) children = childrenGetter.get();
			return children;
		}
		public void reset() {
			if (children != null) Pools.freeAll(children, false);
		}
	}
	public static class InfoList extends MenuList {
		public static InfoList withi(Drawable icon, Prov<String> name) {
			InfoList list = Pools.get(InfoList.class, InfoList::new, max).obtain();
			list.icon = icon;
			list.provider = name;
			list.cons = null;
			return list;
		}
	}


	public static class Countdown implements Runnable, Cons {
		TextButton button;
		int        times;
		public Countdown(TextButton button, int times) {
			this.button = button;
			this.times = times;
			init();
		}
		public Countdown(TextButton button) {
			this(button, 3);
		}
		public void init() {
			button.setDisabled(true);
		}
		public void run() {
			countdown(times, i -> {
				if (i == 0) {
					button.setDisabled(false);
					button.setText("@ok");
				} else {
					button.setText(Core.bundle.get("ok") + "(" + i + ")");
				}
			});
		}
		// ingroed o
		public void get(Object o) {
			run();
		}
	}

	public static class Tooltip extends arc.scene.ui.Tooltip {

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
		public Tooltip(Cons<Table> contents, Runnable show) {
			super(contents, show);
		}
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


	// -----弹窗------
	public interface PopupWindow extends DisposableInterface {}

	public static class InfoFadePopup extends NoTopWindow implements DisposableInterface {
		public InfoFadePopup(String title, float width, float height) {
			super(title, width, height);
			removeCaptureListener(sclListener);
		}
	}
	private static class ExceptionPopup extends Window implements PopupWindow {
		public ExceptionPopup(Throwable exc, String text) {
			super("", 0, 200, false);
			String message = Strings.getFinalMessage(exc);

			cont.margin(15);
			cont.add("@error.title").colspan(2);
			cont.row();
			cont.image().width(300f).pad(2).colspan(2).height(4f).color(Color.scarlet);
			cont.row();
			cont.add((text.startsWith("@") ? Core.bundle.get(text.substring(1)) : text) + (message == null ? "" : "\n[lightgray](" + message + ")"))
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
	public static class ConfirmWindow extends Window implements DisposableInterface, PopupWindow {
		public ConfirmWindow(String title, float minWidth, float minHeight, boolean full, boolean noButtons) {
			super(title, minWidth, minHeight, full, noButtons);
		}

		public void setCenter(Vec2 vec2) {
			setPosition(vec2.x - getPrefWidth() / 2f, vec2.y - getPrefHeight() / 2f);
		}
	}
	private static class AutoFitTable extends Table implements PopupWindow {
		public AutoFitTable() {super(Tex.pane);}
		public float getPrefHeight() {
			return Math.min(super.getPrefHeight(), (float) graphics.getHeight());
		}

		public float getPrefWidth() {
			return Math.min(super.getPrefWidth(), (float) graphics.getWidth());
		}
	}
	public static class InsideTable extends Table {
		public InsideTable() {
		}
		public InsideTable(Drawable background) {
			super(background);
		}
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
}
