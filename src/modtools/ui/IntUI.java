
package modtools.ui;

import arc.Core;
import arc.func.*;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.math.Interp;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.actions.Actions;
import arc.scene.event.ClickListener;
import arc.scene.event.InputEvent;
import arc.scene.style.Drawable;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.*;
import arc.scene.ui.layout.Collapser;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.*;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.ui.Styles;
import modtools.ui.components.Window;

import java.util.Objects;
import java.util.regex.Pattern;

import static mindustry.Vars.mobile;
import static mindustry.Vars.ui;
import static modtools.IntVars.topGroup;

public class IntUI {
	public static final TextureRegionDrawable whiteui = (TextureRegionDrawable) Tex.whiteui;
	public static final MyIcons icons = new MyIcons();

	public static <T extends Element> void doubleClick(T elem, Runnable click, Runnable dclick) {
		elem.addListener(new ClickListener() {
			final Timer.Task clickTask = Time.runTask(20, click);

			@Override
			public void clicked(InputEvent event, float x, float y) {
				super.clicked(event, x, y);
				if (clickTask != null && tapCount >= 2) {
					dclick.run();
					clickTask.cancel();
				} else {
					Timer.schedule(clickTask, 20 / 60f);
				}
			}
		});
	}


	/* 长按事件 */
	public static <T extends Element> T longPress(T elem, final long duration, final Cons<Boolean> func) {
		elem.addListener(new ClickListener() {
			public void clicked(InputEvent event, float x, float y) {
				func.get((Time.millis() - visualPressedTime) > duration);
			}
		});
		return elem;
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
	public static <T extends Button> Table showSelectTable(T button, Cons3<Table, Runnable, String> f, boolean searchable) {
		if (button == null) throw new NullPointerException("button cannot be null");
		Table t = new Table(Tex.button) {
			public float getPrefHeight() {
				return Math.min(super.getPrefHeight(), (float) Core.graphics.getHeight());
			}

			public float getPrefWidth() {
				return Math.min(super.getPrefWidth(), (float) Core.graphics.getWidth());
			}
		};
		Element hitter = new Element();
		Runnable hide = () -> {
			hitter.remove();
			t.actions(Actions.fadeOut(0.3f, Interp.fade), Actions.remove());
		};
		hitter.clicked(hide);
		hitter.fillParent = true;
		Core.scene.add(hitter);
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
			t.table(top -> {
				top.image(Icon.zoom);
				TextField text = new TextField();
				top.add(text).fillX();
				text.changed(() -> {
					f.get(p, hide, text.getText());
				});
			}).padRight(8.0f).fillX().fill().top().row();
		}

		f.get(p, hide, "");
		ScrollPane pane = new ScrollPane(p);
		t.top().add(pane).pad(0.0f).top();
		pane.setScrollingDisabled(true, false);
		t.pack();
		return t;
	}

	public static <T extends Button> Table showSelectListTable(T button, Seq<String> list, Prov<String> holder, Cons<String> cons, int width, int height, Boolean searchable) {
		return showSelectTable(button, (p, hide, text) -> {
			p.clearChildren();

			for (String item : list) {
				p.button(item, Styles.flatt/*Styles.cleart*/, () -> {
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
	public static <T extends Button, T1> Table showSelectImageTableWithIcons(T button, Seq<T1> items, Seq<? extends Drawable> icons, Prov<T1> holder, Cons<T1> cons, float size, float imageSize, int cols, boolean searchable) {
		return showSelectTable(button, (p, hide, text) -> {
			p.clearChildren();
			p.left();
			ButtonGroup<ImageButton> group = new ButtonGroup<>();
			group.setMinCheckCount(0);
			p.defaults().size(size);
			Pattern pattern;

			try {
				pattern = Pattern.compile(text);
			} catch (Exception ex) {
				return;
			}

			int c = 0;

			for (int i = 0; i < items.size; ++i) {
				T1 item = items.get(i);
				if (!text.equals("")) {
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
						t.background(Tex.button).add(item instanceof UnlockableContent ? ((UnlockableContent) item).localizedName : "" + item);
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

	/**
	 * 弹出一个可以选择内容的窗口（无需你提供图标）
	 */
	public static <T extends Button, T1 extends UnlockableContent> Table showSelectImageTable(T button, Seq<T1> items, Prov<T1> holder, Cons<T1> cons, float size, int imageSize, int cols, boolean searchable) {
		Seq<Drawable> icons = new Seq<>();
		items.each(item -> {
			icons.add(new TextureRegionDrawable(item.uiIcon));
		});
		return showSelectImageTableWithIcons(button, items, icons, holder, cons, size, (float) imageSize, cols, searchable);
	}

	/**
	 * 弹出一个可以选择内容的窗口（需你提供图标构造器）
	 */
	public static <T extends Button, T1> Table showSelectImageTableWithFunc(T button, Seq<T1> items, Prov<T1> holder, Cons<T1> cons, float size, int imageSize, int cols, Func<T1, Drawable> func, boolean searchable) {
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
		return new Window("", 0, 200, false) {{
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
			hidden(() -> {
				all.remove(this);
				Time.runTask(30f, this::clearChildren);
			});
		}}.show();
	}

	public static Window showInfoFade(String info) {
		return new Window("info", 0, 64) {{
			cont.add(info);
			// 1.2s
			Time.runTask(60 * 1.2f, () -> {
				hide();
				all.remove(this);
			});
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

	public static class ConfirmWindow extends Window {
		public ConfirmWindow(String title, float minWidth, float minHeight, boolean full, boolean noButtons) {
			super(title, minWidth, minHeight, full, noButtons);
		}

		public void setCenter(Vec2 vec2) {
			setPosition(vec2.x - getPrefWidth() / 2f, vec2.y - getPrefHeight() / 2f);
		}
	}
}
