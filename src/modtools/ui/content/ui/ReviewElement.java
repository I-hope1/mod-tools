package modtools.ui.content.ui;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.actions.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.layout.*;
import arc.util.*;
import arc.util.Timer.Task;
import mindustry.gen.*;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.ui.*;
import modtools.ui.IntUI.MenuList;
import modtools.ui.TopGroup.BackElement;
import modtools.ui.components.input.MyLabel;
import modtools.ui.components.Window;
import modtools.ui.components.Window.DisposableWindow;
import modtools.ui.components.limit.*;
import modtools.ui.content.Content;
import modtools.utils.*;
import modtools.utils.Tools.SR;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static modtools.IntVars.topGroup;
import static modtools.ui.Contents.*;
import static modtools.utils.Tools.*;

public class ReviewElement extends Content {
	public ReviewElement() {
		super("reviewElement");
	}

	public static final boolean    hideSelf  = true;
	public static final LabelStyle skyMyFont = new LabelStyle(MyFonts.MSYHMONO, Color.sky);
	public              Element    frag;
	public              Element    selected, tmp;
	public boolean selecting;
	public boolean cancelEvent;


	// 获取指定位置的元素
	public void getSelected(float x, float y) {
		tmp = Core.scene.root.hit(x, y, true);
		selected = tmp;
		if (tmp != null) {
			/*do {
				selected = tmp;
				tmp.parentToLocalCoordinates(Tmp.v1.set(x, y));
				tmp = selected.hit(x = Tmp.v1.x, y = Tmp.v1.y, true);
			} while (tmp != null && selected != tmp);*/
		}
		// Log.info(selected);
	}

	public static Element             focus;
	/**
	 * focus的来源元素
	 */
	public static Table               focusFrom;
	public static ReviewElementWindow focusWindow;
	public static Color               focusColor = Color.blue.cpy().a(0.4f);

	@Override
	public void load() {
		final Color maskColor = Color.black.cpy().a(0.3f);
		topGroup.drawSeq.add(() -> {
			if (selecting) {
				Draw.color(maskColor);
				Fill.crect(0, 0, Core.graphics.getWidth(), Core.graphics.getHeight());
			}
			if (focus != null) {
				Vec2 vec2  = focus.localToStageCoordinates(Tmp.v2.set(0, 0));
				Vec2 mouse = Core.input.mouse();
				Draw.color();
				Lines.stroke(3f);
				Lines.line(mouse.x, mouse.y, vec2.x + focus.getWidth() / 2f, vec2.y + focus.getHeight() / 2f);
			}
			if (selected == null || !selecting) return true;
			Draw.z(Layer.fogOfWar);
			Draw.color(focusColor);
			Vec2 vec2 = getAbsPos(selected);
			Fill.crect(vec2.x, vec2.y, selected.getWidth(), selected.getHeight());

			return true;
		});
		frag = new BackElement() {
			public void draw() {
				if (focus == null) return;

				Draw.color(focusColor);
				Vec2 vec2 = getAbsPos(focus);
				Fill.crect(vec2.x, vec2.y, focus.getWidth(), focus.getHeight());
				// Vec2 vec2 = Core.camera.unproject(focusFrom.x, focusFrom.y);
			}
		};
		frag.touchable = Touchable.enabled;
		frag.fillParent = true;

		Core.scene.addListener(new InputListener() {
			final Element mask = new Element() {
				{
					fillParent = true;
				}

				@Override
				public Element hit(float x, float y, boolean touchable) {
					return cancelEvent ? this : null;
				}
			};

			/*@Override
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (selecting) {
					last = event.listenerActor.touchable;
					event.listenerActor.touchable = Touchable.disabled;
					// Time.runTask(20, tmp::remove);
					IntVars.async(() -> {
						Element parent = selected.parent;
						while (true) {
							if (parent instanceof ElementShowWindow) return;
							if (parent == null) {
								new ElementShowWindow().show(selected);
								return;
							}
							parent = parent.parent;
						}
					}, () -> {});
				}
				return true;
			}*/

			public void cancel() {
				selecting = false;
			}

			/*public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (last != null) {
					cancel(event);
				}
			}

			public boolean mouseMoved(InputEvent event, float x, float y) {
				if (!selecting) return false;
				getSelected(x, y);
				return true;
			}*/

			@Override
			public boolean keyDown(InputEvent event, KeyCode keycode) {
				if (keycode == KeyCode.escape) {
					cancel();
				}
				return super.keyDown(event, keycode);
			}

			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (event.listenerActor == btn) return false;
				if (!selecting) return false;
				cancelEvent = false;
				// frag.touchable = Touchable.disabled;
				getSelected(x, y);
				topGroup.addChild(mask);
				cancelEvent = true;
				return true;
			}

			public void touchDragged(InputEvent event, float x, float y, int pointer) {
				cancelEvent = false;
				getSelected(x, y);
				cancelEvent = true;
			}

			public boolean filter() {
				if (selected == null) return false;
				Element parent = selected;
				while (parent != null) {
					if (parent instanceof ReviewElementWindow) return false;
					parent = parent.parent;
				}
				return true;
			}

			public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
				// Time.runTask(20, () -> {
				// cancel(actor);
				mask.remove();
				// });
				selecting = false;
				if (filter()) new ReviewElementWindow().show(selected);
			}
		});
		topGroup.addChild(frag);
		// frag.update(() -> frag.toFront());

		btn.update(() -> btn.setChecked(selecting));
		btn.setStyle(Styles.logicTogglet);
	}

	@Override
	public void build() {
		selected = null;
		selecting = !selecting;
	}


	public static final Task CANCEL_TASK = new Task() {
		@Override
		public void run() {
			focus = null;
			focusFrom = null;
		}
	};

	public static class ReviewElementWindow extends DisposableWindow {
		Table   pane    = new LimitTable() {};
		Element element = null;
		Pattern pattern;

		public ReviewElementWindow() {
			super(elementShow.localizedName(), 0, 160, true);
			getCell(cont).maxWidth(Core.graphics.getWidth());

			name = "ReviewElementWindow";

			//			addCloseButton();
			pane.left().defaults().left();
			cont.table(t -> {
				Button[] bs = {null};
				bs[0] = t.button("显示父元素", Icon.up, () -> {
					Runnable go = () -> {
						hide();
						var window = new ReviewElementWindow();
						window.pattern = pattern;
						window.show(element.parent);
						window.setPosition(x, y);
						window.setSize(width, height);
					};
					if (element.parent == Core.scene.root) {
						Vec2 vec2 = bs[0].localToStageCoordinates(new Vec2(0, 0));
						IntUI.showConfirm("父元素为根节点，是否确定", go).setPosition(vec2);
					} else go.run();
				}).disabled(b -> element == null || element.parent == null).width(120).get();
				t.button(Icon.copy, IntStyles.clearNonei, () -> {
					var window = new ReviewElementWindow();
					window.pattern = pattern;
					window.show(element);
					window.setSize(width, height);
				}).padLeft(4f).padRight(4f);
				t.button(Icon.refresh, IntStyles.clearNonei, () -> rebuild(element, pattern)).padLeft(4f).padRight(4f);
				t.table(search -> {
					search.image(Icon.zoom);
					search.field("", str -> rebuild(element, str)).growX();
				}).growX().padLeft(2f);
			}).growX().row();

			cont.add(new ScrollPane(pane) {
				@Override
				public String toString() {
					if (hideSelf) return name;
					return super.toString();
				}
			}).grow();

			update(() -> {
				if (!CANCEL_TASK.isScheduled()) {
					Timer.schedule(CANCEL_TASK, Time.delta / 60f);
				}
			});
		}

		public void rebuild(Element element, String text) {
			pane.clearChildren();

			if (element == null) return;
			Pattern pattern = null;
			try {
				pattern = text.isEmpty() ? null : Pattern.compile(text, Pattern.CASE_INSENSITIVE);
			} catch (Exception ignored) {}
			rebuild(element, pattern);
		}

		public void rebuild(Element element, Pattern pattern) {
			pane.clearChildren();

			if (element == null) return;
			this.pattern = pattern;
			build(element, pane, pattern);

			pane.row();
			pane.image().color(Pal.accent).growX().padTop(10).padBottom(10).row();
			// highlightShowMultiRow(pane, pattern, element + "");
		}


		public void highlightShowMultiRow(Table table, Pattern pattern, String text) {
			if (pattern == null) {
				table.add(new MyLabel(text, IntStyles.myLabel)).color(Pal.accent).growX().left().row();
				table.image().color(JSFunc.underline).fillX().colspan(2).row();
				return;
			}
			table.table(t -> {
				t.left().defaults().left();
				for (var line : text.split("\n")) {
					highlightShow(t, pattern, line);
					t.row();
				}
			}).growX().left().row();
			table.image().color(JSFunc.underline).fillX().colspan(2).row();
		}

		public void highlightShow(Table table, Pattern pattern, String text) {
			if (pattern == null) {
				table.add(text, IntStyles.myLabel).color(Pal.accent);
				return;
			}
			Matcher matcher = pattern.matcher(text);
			table.table(t -> {
				// Font font = style.font;

				int index = 0, lastIndex = 0;
				int times = 0;
				while (index <= text.length() && matcher.find(lastIndex)) {
					times++;
					if (times > 70) {
						IntUI.showException(new Exception("too many"));
						break;
					}
					String curText = matcher.group();
					int    len     = curText.length();
					if (len == 0) break;
					index = matcher.start();
					if (lastIndex != index) t.add(text.substring(lastIndex, index)).color(Pal.accent);
					lastIndex = matcher.end();
					// Log.info("i: @, l: @", index, lastIndex);
					t.table(IntUI.whiteui.tint(Pal.lancerLaser), t1 -> {
						t1.add(new MyLabel(curText, skyMyFont)).padRight(1);
					});
				}
				if (text.length() - lastIndex > 0)
					t.add(text.substring(lastIndex), IntStyles.myLabel).color(Pal.accent);
			});
		}
		public void build(Element element, Table table, Pattern pattern) {
			if (hideSelf) {
				if (element instanceof ReviewElementWindow) {
					table.add("----" + name + "-----", IntStyles.myLabel).row();
					return;
				}
				if (pane.getClass() == element.getClass()) {
					table.add("----" + name + "$pane-----", IntStyles.myLabel).row();
					return;
				}
			}
			table.left().defaults().left().growX();

			try {
				table.add(new Table(wrap -> {
					/* 用于添加侦听器 */
					if (element instanceof Group) {
						/* 占位符 */
						ImageButton button;
						int         size     = 32;
						var         children = ((Group) element).getChildren();
						wrap.add(button = new ImageButton(Icon.rightOpen, Styles.clearNonei))
								.size(size)//.marginLeft(-size).marginRight(-size)
								.disabled(__ -> children.isEmpty());
						// button.translation.set(button.getWidth() / 2f, button.getHeight() / 2f);
						highlightShowMultiRow(wrap, pattern, element == Core.scene.root ? "ROOT"
								: getSimpleName(element.getClass()) + (element.name != null ? ": " + element.name : ""));
						wrap.image().fillY().left()
								.update(t -> t.color.set(focusFrom == wrap ? ColorFul.color : Color.darkGray));
						wrap.defaults().growX();
						wrap.add(new LimitTable(t -> {
							if (children.isEmpty()) {
								return;
							}
							// t.marginLeft(size / 4f);
							Table table1 = new Table();
							for (var child : children) {
								build(child, table1, pattern);
							}
							Cell<?>         _cell   = t.add(table1).grow();
							final boolean[] checked = {children.size < 20};
							button.clicked(() -> checked[0] = !checked[0]);
							Image image = button.getImage();
							button.update(() -> {
								button.setOrigin(Align.center);
								if (checked[0]) {
									image.actions(Actions.rotateTo(-90, 0.1f));
									_cell.setElement(table1);
								} else {
									image.actions(Actions.rotateTo(0, 0.1f));
									_cell.clearElement();
								}
							});
						})).left();
					} else if (element instanceof Image) {
						wrap.table(p0 -> {
							p0.table(Window.myPane, p -> {
								try {
									int   size = 32;
									float mul  = element.getHeight() / element.getWidth();
									p.add(new Image(((Image) element).getDrawable())).color(element.color)
											.size(size / Scl.scl(), size * mul / Scl.scl());
								} catch (Throwable e) {
									p.add("空图像").labelAlign(Align.left);
								}
							});
							// 用于补位
							p0.add().growX();
						}).growX().get();
					} else {
						wrap.defaults().growX();
						highlightShowMultiRow(wrap, pattern, String.valueOf(element));
					}
					// JSFunc.addStoreButton(wrap, "element", () -> element);
					Element elem = wrap.getChildren().get(wrap.getChildren().size > 1 ? 1 : 0);
					Runnable copy = () -> {
						IntUI.showInfoFade(Core.bundle.format("jsfunc.saved", tester.put(element))).setPosition(Core.input.mouse());
					};
					IntUI.addShowMenuLinstenr(elem, new MenuList(Icon.copy, "@jsfunc.store_as_js_var2", copy),
					                          new MenuList(Icon.trash, "@clear", () -> element.remove()),
					                          new MenuList(Icon.info, "@details", () -> JSFunc.showInfo(element)));
					IntUI.doubleClick(elem, () -> {}, copy);
					wrap.touchable = Touchable.enabled;
				}) {

					@Override
					public Element hit(float x, float y, boolean touchable) {
						focus = null;
						Element tmp = super.hit(x, y, touchable);
						if (tmp == null) return null;
						// color.set(Color.white);
						if (focus != null) {
							if (CANCEL_TASK.isScheduled()) CANCEL_TASK.cancel();
							return tmp;
						}
						focus = element;
						focusFrom = this;
						if (CANCEL_TASK.isScheduled()) CANCEL_TASK.cancel();
						return tmp;
					}

					{
						update(() -> background(focusFrom == this ? Styles.black8 : Styles.none));
					}
				});
			} catch (Exception e) {
				//				Vars.ui.showException(e);
				Log.err(e);
			}

			table.row();
		}

		public void show(Element element) {
			this.element = element;
			((ScrollPane) pane.parent).setScrollY(0);
			IntVars.async(() -> {
				rebuild(element, "");
			}, this::show);
			// 不知道为什么，这样就可以显示全面
			// Vars.ui.showInfoFade("[clear]额");

			//			Vars.ui.loadSync();
			//			Time.runTask(1, () -> {
				/*int i = 0;
				String prefix = "temp";
				while (ScriptableObject.hasProperty(tester.scope, prefix + i)) {
					i++;
				}*/
			//				tester.put(prefix + i, "ikzak");
			//			});
			/*Time.runTask(1, () -> {
				for (int i = 0; i < 1E6; i++) ;
			});*/
		}

		public String toString() {
			if (hideSelf) return name;
			return super.toString();
		}

		public Element hit(float x, float y, boolean touchable) {
			Element elem = super.hit(x, y, touchable);
			if (elem != null && elem.isDescendantOf(this)) {
				focusWindow = this;
			}
			return elem;
		}
	}

	public static String getSimpleName(Class<?> clazz) {
		if (!Group.class.isAssignableFrom(clazz)) return clazz.getSimpleName();
		while (clazz.getSimpleName().isEmpty() && clazz != Group.class) {
			clazz = clazz.getSuperclass();
		}
		return clazz.getSimpleName();
	}
}
