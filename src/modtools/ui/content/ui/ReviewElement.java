package modtools.ui.content.ui;

import arc.Core;
import arc.func.Cons;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.actions.Actions;
import arc.scene.event.Touchable;
import arc.scene.ui.*;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.layout.*;
import arc.util.*;
import arc.util.Timer.Task;
import mindustry.gen.Icon;
import mindustry.graphics.*;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.ui.*;
import modtools.ui.TopGroup.Drawer;
import modtools.ui.components.Window;
import modtools.ui.components.Window.DisposableInterface;
import modtools.ui.components.input.MyLabel;
import modtools.ui.components.limit.LimitTable;
import modtools.ui.content.Content;
import modtools.utils.*;

import java.util.regex.*;

import static modtools.ui.Contents.elementShow;
import static modtools.ui.IntStyles.*;
import static modtools.ui.IntUI.*;
import static modtools.utils.Tools.getAbsPos;

public class ReviewElement extends Content {
	public ReviewElement() {
		super("reviewElement");
	}

	public static final boolean    hideSelf  = true;
	public static final LabelStyle skyMyFont = new LabelStyle(MyFonts.MSYHMONO, Color.sky);


	public static Element             focus;
	/**
	 * focus的来源元素
	 */
	public static Table               focusFrom;
	public static ReviewElementWindow focusWindow;
	public static Color               focusColor = Color.blue.cpy().a(0.4f);

	public static final Color  maskColor = Color.black.cpy().a(0.3f);
	public static final Drawer drawer    = (selecting, selected) -> {
		if (!selecting) return;
		/* 绘制遮罩 */
		Draw.color(maskColor);
		Fill.crect(0, 0, Core.graphics.getWidth(), Core.graphics.getHeight());

		/* 绘制选择提示 */
		Draw.z(Layer.fogOfWar);
		drawFocus(selected);
	};

	public void load() {
		topGroup.drawSeq.add(() -> {
			if (focus == null) return true;

			Vec2 vec2  = focus.localToStageCoordinates(Tmp.v2.set(0, 0));
			Vec2 mouse = Core.input.mouse();
			Draw.color(ColorFul.color);
			Lines.stroke(4f);
			Lines.line(mouse.x, mouse.y, vec2.x + focus.getWidth() / 2f, vec2.y + focus.getHeight() / 2f);

			return true;
		});
		topGroup.backDrawSeq.add(() -> {
			if (focus != null) drawFocus(focus);
			return true;
			// Vec2 vec2 = Core.camera.unproject(focusFrom.x, focusFrom.y);
		});
		// frag.update(() -> frag.toFront());

		btn.update(() -> btn.setChecked(topGroup.isSelecting()));
		btn.setStyle(Styles.logicTogglet);

		TopGroup.searchBlackList.add(btn);
		TopGroup.classBlackList.add(ReviewElementWindow.class);
	}
	private static void drawFocus(Element elem) {
		Draw.color(focusColor);
		Vec2 vec2 = getAbsPos(elem);
		Fill.crect(vec2.x, vec2.y, elem.getWidth(), elem.getHeight());

		Draw.color(Pal.accent);
		Lines.stroke(1f);
		Drawf.dashRectBasic(vec2.x, vec2.y, elem.getWidth(), elem.getHeight());
	}

	public static final Cons<Element> callback = selected -> new ReviewElementWindow().show(selected);
	public void build() {
		topGroup.requestSelectElem(drawer, callback);
	}


	public static final Task CANCEL_TASK = new Task() {
		@Override
		public void run() {
			focus = null;
			focusFrom = null;
		}
	};

	public static class ReviewElementWindow extends Window implements DisposableInterface {
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
				bs[0] = t.button("@reviewElement.parent", Icon.up, () -> {
					Runnable go = () -> {
						hide();
						var window = new ReviewElementWindow();
						window.pattern = pattern;
						// Log.info(element.parent);
						window.show(element.parent);
						window.setPosition(x, y);
						window.setSize(width, height);
					};
					if (element.parent == Core.scene.root) {
						Vec2 vec2 = bs[0].localToStageCoordinates(new Vec2(0, 0));
						IntUI.showConfirm("@reviewElement.confirm.root", go).setPosition(vec2);
					} else go.run();
				}).disabled(b -> element == null || element.parent == null).width(120).get();
				t.button(Icon.copy, clearNonei, () -> {
					var window = new ReviewElementWindow();
					window.pattern = pattern;
					window.show(element);
					window.setSize(width, height);
				}).padLeft(4f).padRight(4f);
				t.button(Icon.refresh, clearNonei, () -> rebuild(element, pattern)).padLeft(4f).padRight(4f);
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
			}).grow().minHeight(90);

			update(() -> {
				if (!CANCEL_TASK.isScheduled()) {
					Timer.schedule(CANCEL_TASK, Time.delta / 60f);
				}
			});
		}

		public void rebuild(Element element, String text) {
			pane.clearChildren();

			if (element == null) return;
			Pattern pattern = Tools.complieRegExpCatch(text);
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

		/** 结构： Label，Image（下划线） */
		public void highlightShowMultiRow(Table table, Pattern pattern, String text) {
			if (pattern == null) {
				table.add(new MyLabel(text, MOMO_Label)).growX().left().color(Pal.accent).row();
				table.image().color(JSFunc.underline).growX().colspan(2).row();
				return;
			}
			table.table(t -> {
				t.left().defaults().left();
				for (var line : text.split("\n")) {
					highlightShow(t, pattern, line);
					t.row();
				}
			}).growX().left().row();
			table.image().color(JSFunc.underline).growX().colspan(2).row();
		}

		public void highlightShow(Table table, Pattern pattern, String text) {
			if (pattern == null) {
				table.add(text, MOMO_Label).color(Pal.accent);
				return;
			}
			Matcher matcher = pattern.matcher(text);
			table.table(t -> {
				// Font font = style.font;

				int index = 0, lastIndex = 0;
				while (index <= text.length() && matcher.find(lastIndex)) {
					String curText = matcher.group();
					int    len     = curText.length();
					if (len == 0) break;
					index = matcher.start();
					if (lastIndex != index) t.add(text.substring(lastIndex, index)).color(Pal.accent);
					lastIndex = matcher.end();
					if (index == lastIndex) {
						IntUI.showException(new Exception("too many"));
						break;
					}
					// Log.info("i: @, l: @", index, lastIndex);
					t.table(IntUI.whiteui.tint(Pal.logicWorld), t1 -> {
						t1.add(new MyLabel(curText, skyMyFont)).padRight(1);
					});
				}
				if (text.length() - lastIndex > 0)
					t.add(text.substring(lastIndex), MOMO_Label).color(Pal.accent);
			});
		}
		public void build(Element element, Table table, Pattern pattern) {
			if (hideSelf) {
				if (element instanceof ReviewElementWindow) {
					table.add("----" + name + "-----", MOMO_Label).row();
					return;
				}
				/*if (pane.getClass() == element.getClass()) {
					table.add("----" + name + "$pane-----", IntStyles.myLabel).row();
					return;
				}*/
			}
			table.left().defaults().left().growX();

			try {
				table.add(new MyWrapTable(this, element, pattern));
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

	private static class MyWrapTable extends LimitTable {
		private final Element element;
		/** Group的子元素数量 */
		// private final ReviewElementWindow window;
		public MyWrapTable(ReviewElementWindow window, Element element, Pattern pattern) {
			super(wrap -> {
				// ((ScrollPane) pane.parent).setScrollingDisabled(true, true);
				int childIndex;
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
					childIndex = 1;
					window.highlightShowMultiRow(wrap, pattern, element == Core.scene.root ? "ROOT"
							: ReviewElement.getSimpleName(element.getClass()) + (element.name != null ? ": " + element.name : ""));
					wrap.image().growY().left()
							.update(t -> t.color.set(ReviewElement.focusFrom == wrap ? ColorFul.color : Color.darkGray));
					wrap.defaults().growX();
					wrap.add(new LimitTable(t -> {
						/*if (children.isEmpty()) {
							return;
						}*/
						// t.marginLeft(size / 4f);
						Table table1 = new Table();
						Runnable rebuild = () -> {
							table1.clearChildren();
							for (var child : children) {
								window.build(child, table1, pattern);
							}
						};
						rebuild.run();
						Cell<?>         _cell            = t.add(table1).grow();
						final boolean[] checked          = {!children.isEmpty() && children.size < 20};
						int[]           lastChildrenSize = {children.size};
						button.clicked(() -> checked[0] = !checked[0]);
						Image image = button.getImage();
						button.update(() -> {
							button.setOrigin(Align.center);
							if (checked[0]) {
								if (lastChildrenSize[0] != children.size) {
									lastChildrenSize[0] = children.size;
									rebuild.run();
								}
								image.actions(Actions.rotateTo(-90, 0.1f));
								_cell.setElement(table1);
							} else {
								image.actions(Actions.rotateTo(0, 0.1f));
								_cell.clearElement();
							}
						});
					})).left();
					// Log.info(wrap);
				} else if (element instanceof Image) {
					childIndex = 0;
					wrap.table(p0 -> {
						// Tooltip tooltip = new Tooltip(t -> t.image(((Image) element).getDrawable())) ;
						// tooltip.allowMobile = true;
						// p0.addListener(tooltip);
						p0.table(Window.myPane, p -> {
							try {
								int   size = 32;
								float mul  = element.getHeight() / element.getWidth();
								p.add(new Image(((Image) element).getDrawable())).color(element.color)
										.size(size, size * mul);
							} catch (Throwable e) {
								p.add("空图像").labelAlign(Align.left);
							}
						});
						// 用于补位
						p0.add().growX();
					}).growX().get();
				} else {
					wrap.defaults().growX();
					childIndex = 0;
					window.highlightShowMultiRow(wrap, pattern, String.valueOf(element));
				}
				// JSFunc.addStoreButton(wrap, "element", () -> element);
				Element _elem = wrap.getChildren().get(childIndex);
				Runnable copy = () -> {
					Contents.tester.put(Core.input.mouse(), element);
				};
				IntUI.addShowMenuListener
						(_elem, new MenuList(Icon.copy, "@jsfunc.store_as_js_var2", copy),
								new ConfirmList(Icon.trash, "@clear", "@confirm.remove", () -> element.remove()),
								new MenuList(Icon.copy, "@copy.path", () -> {
									JSFunc.copyText(getPath(element));
								}),
								new MenuList(Icon.fileImage, "@reviewElement.screenshot", () -> {
									Tools.quietScreenshot(element);
								}),
								new MenuList(Icon.adminSmall, "@settings.debugbounds", () -> JSFunc.toggleDrawPadElem(element)),
								new MenuList(Icon.info, "@details", () -> JSFunc.showInfo(element))
						);
				IntUI.doubleClick(_elem, () -> {}, copy);
				wrap.touchable = Touchable.enabled;
			});
			this.element = element;
			// this.window = window;
			update(() -> background(focusFrom == this ? Styles.flatDown : Styles.none));
		}
		private static CharSequence getPath(Element element) {
			if (element == null) return "null";
			Element       el = element;
			StringBuilder sb = new StringBuilder();
			while (el != null) {
				if (el.name != null) {
					return "Core.scene.find(\"" + el.name + "\")" + sb;
				} else {
					sb.append(".children.get(").append(el.getZIndex()).append(')');
					el = el.parent;
				}
			}
			return element.getScene() != null ? "Core.scene.root" + sb : sb.delete(0, 0);
		}
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

		static int getDeep(Element element) {
			int deep = 0;
			while (element.parent != null) {
				element = element.parent;
				deep++;
			}
			return deep;
		}
		/** 获取最近的Wrap父节点 */
		public MyWrapTable _getParent(Element element) {
			Element element1 = element;
			while (element1 != null) {
				if (element1 instanceof MyWrapTable) return (MyWrapTable) element1;
				element1 = element1.parent;
			}
			return null;
		}

		/*{
			addListener(new InputListener() {
				public final Vec2 lastMouse = new Vec2(), lastOff = new Vec2();
				public boolean touchDown(InputEvent event, float x, float y, int __, KeyCode button) {
					MyWrapTable p = _getParent(hit(x, y, true));
					// Log.info(getDeep(event.listenerActor));
					lastMouse.set(Core.input.mouse());
					lastOff.set(event.listenerActor.translation);
					if (p == MyWrapTable.this) {
						((ScrollPane) window.pane.parent).setScrollingDisabledY(true);
						toFront();
						return true;
					}
					return false;
				}
				public void touchDragged(InputEvent event, float x, float y, int pointer) {
					event.listenerActor.translation.y = Core.input.mouseY() - lastMouse.y + lastOff.y;
				}
				public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
					((ScrollPane) window.pane.parent).setScrollingDisabledY(false);
				}
			});
		}*/
	}
}
