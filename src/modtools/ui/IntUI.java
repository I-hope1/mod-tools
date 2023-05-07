
package modtools.ui;

import arc.*;
import arc.func.*;
import arc.graphics.Color;
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
import arc.util.Timer;
import arc.util.Timer.Task;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.*;
import mindustry.ui.Styles;
import modtools.ui.components.Window;
import modtools.ui.components.Window.*;
import modtools.utils.*;

import java.lang.reflect.Array;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static mindustry.Vars.*;

public class IntUI {
	public static final TextureRegionDrawable whiteui  = (TextureRegionDrawable) Tex.whiteui;
	public static final Frag                  frag     = new Frag();
	public static final TopGroup              topGroup = new TopGroup();

	public static void load() {
		if (frag.getChildren().isEmpty()) {
			frag.load();
		} else {
			topGroup.addChild(frag);
		}
	}

	public static final float   DEF_DURATION = 0.2f;
	public static final MyIcons icons        = new MyIcons();

	public static <T extends Element> T
	doubleClick(T elem, Runnable click, Runnable dclick) {
		elem.addListener(new ClickListener() {
			final Task clickTask = new Task() {
				public void run() {
					click.run();
				}
			};
			public void clicked(InputEvent event, float x, float y) {
				super.clicked(event, x, y);
				if (clickTask.isScheduled()) {
					dclick.run();
					clickTask.cancel();
				} else {
					Timer.schedule(clickTask, 0.25f);
				}
			}
		});
		return elem;
	}


	/* 长按事件 */
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
				if (task.isScheduled()) {
					task.cancel();
					if (pressed) boolc.get(false);
				}
				super.touchUp(event, x, y, pointer, button);
			}
		});
		return elem;
	}

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
	 * long press for mobile
	 * r-click for desktop
	 */
	public static <T extends Element> T
	longPressOrRclick(T element, Consumer<T> run) {
		return mobile ? longPress(element, 600, b -> {
			if (b) run.accept(element);
		}) : rightClick(element, () -> run.accept(element));
	}

	public static void
	addShowMenuListener(Element elem, MenuList... list) {
		longPressOrRclick(elem, __ -> {
			showSelectTableRB(Core.input.mouse().cpy(), (p, hide, ___) -> {
				for (var menu : list) {
					p.button(menu.name, menu.icon, Styles.cleart, () -> {
						menu.run.run();
						hide.run();
					}).size(120, 42).row();
				}
			}, false);
		});
	}
	public static void
	addShowMenuListener(Element elem, Iterable<MenuList> list) {
		longPressOrRclick(elem, __ -> {
			showSelectTableRB(Core.input.mouse().cpy(), (p, hide, ___) -> {
				for (var menu : list) {
					p.button(menu.name, menu.icon, Styles.cleart, () -> {
						menu.run.run();
						hide.run();
					}).size(120, 42).row();
				}
			}, false);
		});
	}
	public static void
	addShowMenuListener(Element elem, ArrayList<MenuList> list) {
		longPressOrRclick(elem, __ -> {
			showSelectTableRB(Core.input.mouse().cpy(), (p, hide, ___) -> {
				for (var menu : list) {
					p.button(menu.name, menu.icon, Styles.cleart, () -> {
						menu.run.run();
						hide.run();
					}).size(120, 42).row();
				}
			}, false);
		});
	}

	/**
	 * 在鼠标右下弹出一个小窗，自己设置内容
	 *
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
		Table t = new Table(Styles.black8) {
			public float getPrefHeight() {
				return Math.min(super.getPrefHeight(), (float) Core.graphics.getHeight());
			}

			public float getPrefWidth() {
				return Math.min(super.getPrefWidth(), (float) Core.graphics.getWidth());
			}
		};
		t.margin(6, 8, 6, 8);
		Element hitter = new Element();
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
			t.setPosition(Tmp.v1.x, Tmp.v1.y, Align.bottomRight);
			if (t.getWidth() > Core.scene.getWidth()) {
				t.setWidth((float) Core.graphics.getWidth());
			}

			if (t.getHeight() > Core.scene.getHeight()) {
				t.setHeight((float) Core.graphics.getHeight());
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
		t.top().add(pane).pad(0.0f).top();
		pane.setScrollingDisabled(true, false);
		t.pack();
		return t;
	}

	/**
	 * 弹出一个小窗，自己设置内容
	 *
	 * @param button     用于定位弹窗的位置
	 * @param f          (p, hide, text)
	 *                   p 是Table，你可以添加元素
	 *                   hide 是一个函数，调用就会关闭弹窗
	 *                   text 如果 @param 为 true ，则启用。用于返回用户在搜索框输入的文本
	 * @param searchable 可选，启用后会添加一个搜索框
	 */
	public static <T extends Button> Table
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
				t.setPosition(Tmp.v1.x, Tmp.v1.y, 1);
				if (t.getWidth() > Core.scene.getWidth()) {
					t.setWidth((float) Core.graphics.getWidth());
				}

				if (t.getHeight() > Core.scene.getHeight()) {
					t.setHeight((float) Core.graphics.getHeight());
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
		return showSelectTable(button, (p, hide, text) -> {
			p.clearChildren();

			for (String item : list) {
				p.button(item, IntStyles.flatt/*Styles.cleart*/, () -> {
					cons.get(item);
					hide.run();
				}).size((float) width, (float) height).disabled(Objects.equals(holder.get(), item)).row();
			}

		}, searchable);
	}

	/**
	 * 弹出一个可以选择内容的窗口（类似物品液体源的选择）
	 * （需要提供图标）
	 *
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
		return showSelectTable(button, (p, hide, text) -> {
			p.clearChildren();
			p.left();
			ButtonGroup<ImageButton> group = new ButtonGroup<>();
			group.setMinCheckCount(0);
			p.defaults().size(size);
			Pattern pattern;

			try {
				pattern = Tools.complieRegExp(text);
			} catch (Exception ex) {
				return;
			}

			int c = 0;

			for (int i = 0; i < items.size; ++i) {
				T1 item = items.get(i);
				if (!text.isEmpty()) {
					if (item instanceof UnlockableContent) {
						UnlockableContent unlock;
						if (!pattern.matcher((unlock = (UnlockableContent) item).name).find() && !pattern.matcher(unlock.localizedName).find()) {
							continue;
						}
					} else if (!pattern.matcher("" + item).find()) {
						continue;
					}
				}

				ImageButton btn = p.button(Tex.whiteui, Styles.clearTogglei, imageSize, () -> {
					cons.get(item);
					hide.run();
				}).size(size).get();
				if (!mobile) {
					btn.addListener(new Tooltip(t -> {
						t.background(Tex.button).add(item instanceof UnlockableContent ? ((UnlockableContent) item).localizedName : "" + item)
								.right().bottom();
					}));
				}

				btn.getStyle().imageUp = icons.get(i);
				btn.update(() -> {
					button.setChecked(holder.get() == item);
				});
				++c;
				if (c % cols == 0) {
					p.row();
				}
			}

		}, searchable);
	}
	public static <T1> Table
	showSelectImageTableWithIcons(Vec2 vec2, Seq<T1> items,
	                              Seq<? extends Drawable> icons,
	                              Prov<T1> holder, Cons<T1> cons, float size,
	                              float imageSize, int cols,
	                              boolean searchable) {
		return showSelectTable(vec2, (p, hide, text) -> {
			p.clearChildren();
			p.left();
			ButtonGroup<ImageButton> group = new ButtonGroup<>();
			group.setMinCheckCount(0);
			p.defaults().size(size);
			Pattern pattern;

			try {
				pattern = Tools.complieRegExp(text);
			} catch (Exception ex) {
				return;
			}

			int c = 0;

			for (int i = 0; i < items.size; ++i) {
				T1 item = items.get(i);
				if (!text.isEmpty()) {
					if (item instanceof UnlockableContent) {
						UnlockableContent unlock;
						if (!pattern.matcher((unlock = (UnlockableContent) item).name).find() && !pattern.matcher(unlock.localizedName).find()) {
							continue;
						}
					} else if (!pattern.matcher("" + item).find()) {
						continue;
					}
				}

				ImageButton btn = p.button(Tex.whiteui, Styles.clearTogglei, imageSize, () -> {
					cons.get(item);
					hide.run();
				}).size(size).get();
				if (!mobile) {
					btn.addListener(new Tooltip(t -> {
						t.background(Tex.button).add(item instanceof UnlockableContent ? ((UnlockableContent) item).localizedName : "" + item)
								.right().bottom();
					}));
				}

				btn.getStyle().imageUp = icons.get(i);
				++c;
				if (c % cols == 0) {
					p.row();
				}
			}

		}, searchable);
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
				t.setWidth((float) Core.graphics.getWidth());
			}

			if (t.getHeight() > Core.scene.getHeight()) {
				t.setHeight((float) Core.graphics.getHeight());
			}

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
	 * 弹出一个可以选择内容的窗口（无需你提供图标，需要提供构造器）
	 */
	public static <T extends Button, T1 extends UnlockableContent> Table
	showSelectImageTable(T button, Seq<T1> items,
	                     Prov<T1> holder,
	                     Cons<T1> cons, float size,
	                     int imageSize, int cols,
	                     boolean searchable) {
		Drawable[] icons = (Drawable[]) Array.newInstance(Drawable.class, items.size);
		for (int i = 0; i < items.size; i++) {
			icons[i] = new TextureRegionDrawable(items.get(i).uiIcon);
		}
		return showSelectImageTableWithIcons(button, items, new Seq<>(icons), holder, cons, size, (float) imageSize, cols, searchable);
	}
	/**
	 * 弹出一个可以选择内容的窗口（无需你提供图标，需要提供构造器）
	 */
	public static <T1 extends UnlockableContent> Table
	showSelectImageTable(Vec2 vec2, Seq<T1> items,
	                     Prov<T1> holder,
	                     Cons<T1> cons, float size,
	                     int imageSize, int cols,
	                     boolean searchable) {
		Drawable[] icons = (Drawable[]) Array.newInstance(Drawable.class, items.size);
		for (int i = 0; i < items.size; i++) {
			icons[i] = new TextureRegionDrawable(items.get(i).uiIcon);
		}
		return showSelectImageTableWithIcons(vec2, items, new Seq<>(icons), holder, cons, size, (float) imageSize, cols, searchable);
	}

	/**
	 * 弹出一个可以选择内容的窗口（需你提供图标构造器）
	 */
	public static <T extends Button, T1> Table
	showSelectImageTableWithFunc(T button, Seq<T1> items, Prov<T1> holder,
	                             Cons<T1> cons, float size, int imageSize,
	                             int cols, Func<T1, Drawable> func,
	                             boolean searchable) {
		Seq<Drawable> icons = new Seq<>();
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

	public static Window showException(String text, Throwable exc) {
		ui.loadfrag.hide();
		return new ExceptionPopup(exc, text).show();
	}

	public static Window showInfoFade(String info) {
		return new InfoFadePopup("info", 0, 64) {{
			cont.add(info);
			// 1.2s
			Time.runTask(60 * 1.2f, this::hide);
			// Time.runTask(0, this::display);
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
		window.hidden(() -> Window.all.remove(window));
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
		window.show();
		return window;
	}

	/* 整数倒计时 */
	public static void countdown(int times, Intc cons) {
		int[] i = {times};
		Timer.schedule(() -> {
			cons.get(i[0]);
			i[0]--;
		}, 0, 1, times);
	}

	// static {
	// 	Time.run(0, () -> {
	// 		new BaseDialog("a") {{
	// 			addCloseButton();
	// 			TextButton button = (TextButton) buttons.getChildren().first();
	// 			shown(new Countdown(button));
	// 			show();
	// 		}};
	// 	});
	// }

	public static class ConfirmWindow extends Window implements DisposableInterface, PopupWindow {
		public ConfirmWindow(String title, float minWidth, float minHeight, boolean full, boolean noButtons) {
			super(title, minWidth, minHeight, full, noButtons);
		}

		public void setCenter(Vec2 vec2) {
			setPosition(vec2.x - getPrefWidth() / 2f, vec2.y - getPrefHeight() / 2f);
		}
	}


	public static class MenuList {
		public Drawable icon;
		public String   name;
		public Runnable run;

		public MenuList(Drawable icon, String name, Runnable run) {
			this.icon = icon;
			this.name = name;
			this.run = run;
		}
	}

	public static class ConfirmList extends MenuList {
		public ConfirmList(Drawable icon, String name, String text, Runnable run) {
			super(icon, name, () -> {
				IntUI.showConfirm(text, run);
			});
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
			super(contents);
			show = () -> topGroup.addChild(getContainer());
		}
		public Tooltip(Cons<Table> contents, Runnable show) {
			super(contents, show);
		}
		public Tooltip(Cons<Table> contents, Tooltips manager) {
			super(contents, manager);
		}
	}


	public static class InfoFadePopup extends Window implements DisposableInterface, PopupWindow {
		public InfoFadePopup(String title, float width, float height) {
			super(title, width, height);
		}
	}

	public interface PopupWindow {}

	private static class ExceptionPopup extends Window implements DisposableInterface, PopupWindow {
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

	private static class AutoFitTable extends Table implements PopupWindow {
		public AutoFitTable() {super(Tex.button);}
		public float getPrefHeight() {
			return Math.min(super.getPrefHeight(), (float) Core.graphics.getHeight());
		}

		public float getPrefWidth() {
			return Math.min(super.getPrefWidth(), (float) Core.graphics.getWidth());
		}
	}
}
