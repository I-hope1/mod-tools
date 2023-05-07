package modtools.ui.content.ui;

import arc.Core;
import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.actions.Actions;
import arc.scene.event.Touchable;
import arc.scene.ui.*;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.layout.*;
import arc.struct.Seq;
import arc.util.*;
import arc.util.Timer.Task;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.ui.*;
import modtools.ui.TopGroup.FocusTask;
import modtools.ui.components.ListDialog.ModifiedLabel;
import modtools.ui.components.Window;
import modtools.ui.components.Window.DisposableInterface;
import modtools.ui.components.input.MyLabel;
import modtools.ui.components.limit.LimitTable;
import modtools.ui.content.Content;
import modtools.utils.*;

import java.util.regex.*;

import static modtools.ui.Contents.reviewElement;
import static modtools.ui.IntStyles.*;
import static modtools.ui.IntUI.*;
import static modtools.utils.Tools.*;

public class ReviewElement extends Content {
	private static final float duration = 0.1f;
	public ReviewElement() {
		super("reviewElement");
	}

	public static final boolean    hideSelf  = true;
	public static final LabelStyle skyMyFont = new LabelStyle(MyFonts.def, Color.sky);


	public static Element             focus;
	/**
	 * focus的来源元素
	 */
	public static Table               focusFrom;
	public static ReviewElementWindow focusWindow;
	public static Color               focusColor = DEF_FOCUS_COLOR;

	public static final Color maskColor = DEF_MASK_COLOR;

	public void load() {
		topGroup.focusOnElement(new FocusTask(maskColor, focusColor) {
			{drawSlightly = true;}

			public void backDraw() {
				if (focus != null) drawFocus(focus);
			}
			public void elemDraw() {
			}
			public void drawLine() {
				if (focus == null) return;

				Vec2 vec2  = focus.localToStageCoordinates(Tmp.v2.set(0, 0));
				Vec2 mouse = Core.input.mouse();
				Draw.color(ColorFul.color);
				Lines.stroke(4f);
				Lines.line(mouse.x, mouse.y, vec2.x + focus.getWidth() / 2f, vec2.y + focus.getHeight() / 2f);
			}
			public void endDraw() {
				if (topGroup.isSelecting()) super.endDraw();
				drawLine();
				elem = topGroup.getSelected();
				if (elem != null) super.elemDraw();
			}
		});
		// frag.update(() -> frag.toFront());

		btn.update(() -> btn.setChecked(topGroup.isSelecting()));
		btn.setStyle(Styles.logicTogglet);

		TopGroup.searchBlackList.add(btn);
		TopGroup.classBlackList.add(ReviewElementWindow.class);
	}

	public static final Cons<Element> callback = selected -> new ReviewElementWindow().show(selected);
	public void build() {
		topGroup.requestSelectElem(null, callback);
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
			super(reviewElement.localizedName(), 20, 160, true);
			getCell(cont).maxWidth(Core.graphics.getWidth());

			name = "ReviewElementWindow";

			//			addCloseButton();
			pane.left().defaults().left();
			cont.table(t -> {
				Button[] bs = {null};
				bs[0] = t.button("@reviewElement.parent", Icon.up, () -> {
					 Runnable go = () -> {
						 var window = new ReviewElementWindow();
						 // Log.info(element.parent);
						 window.setPosition(x, y);
						 window.setSize(width, height);
						 window.pattern = pattern;
						 window.show(element.parent);
						 hide();
					 };
					 if (element.parent == Core.scene.root) {
						 Vec2 vec2 = getAbsPos(bs[0]);
						 IntUI.showConfirm("@reviewElement.confirm.root", go).setPosition(vec2);
					 } else go.run();
				 })
				 .disabled(b -> element == null || element.parent == null)
				 .width(120).get();
				t.button(Icon.copy, clearNonei, () -> {
					var window = new ReviewElementWindow();
					window.pattern = pattern;
					window.setSize(width, height);
					window.show(element);
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
			Pattern pattern = Tools.compileRegExpCatch(text);
			rebuild(element, pattern);
		}

		public void rebuild(Element element, Pattern pattern) {
			pane.clearChildren();

			if (element == null) return;
			this.pattern = pattern;
			build(element, pane);

			pane.row();
			pane.image().color(Pal.accent).growX().padTop(8).padBottom(8).row();
			// highlightShowMultiRow(pane, pattern, element + "");
		}

		/** 结构： Label，Image（下划线） */
		public void highlightShowMultiRow(Table table, String text) {
			if (pattern == null) {
				table.add(new MyLabel(text, MOMO_LabelStyle)).growX().left().color(Pal.accent).row();
				table.image().color(JSFunc.c_underline).growX().colspan(2).row();
				return;
			}
			table.table(t -> {
				t.left().defaults().left();
				for (var line : text.split("\\n")) {
					highlightShow(t, pattern, line);
					t.row();
				}
			}).growX().left().row();
			table.image().color(JSFunc.c_underline).growX().colspan(2).row();
		}

		public void highlightShow(Table table, Pattern pattern, String text) {
			if (pattern == null) {
				table.add(text, MOMO_LabelStyle).color(Pal.accent);
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
					t.add(text.substring(lastIndex), MOMO_LabelStyle).color(Pal.accent);
			});
		}
		public void build(Element element, Table table) {
			if (element == null) throw new IllegalArgumentException("element is null");
			if (hideSelf && element instanceof ReviewElementWindow) {
				table.add("----" + name + "-----", MOMO_LabelStyle).row();
				return;
			}
			table.left().defaults().left().growX();

			Core.app.post(() -> {
				try {
					table.add(new MyWrapTable(this, element));
				} catch (Exception e) {
					Log.err(e);
				}

				table.row();
			});
		}

		public void show(Element element) {
			this.element = element;
			((ScrollPane) pane.parent).setScrollY(0);
			IntVars.async(() -> rebuild(element, pattern),
			 this::show);
		}

		public String toString() {
			if (hideSelf) return name;
			return super.toString();
		}

		public Element hit(float x, float y, boolean touchable) {
			Element elem = super.hit(x, y, touchable);
			if (elem != null && elem.isDescendantOf(this)) {
				frontWindow = this;
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
		public MyWrapTable(ReviewElementWindow window, Element element) {
			this.element = element;
			/* 用于下面的侦听器  */
			int childIndex;
			/* 用于添加侦听器 */
			if (element instanceof Group group) {
				/* 占位符 */
				ImageButton  button   = new ImageButton(Icon.rightOpen, Styles.clearNonei);
				int          size     = 32;
				Seq<Element> children = group.getChildren();
				add(button).size(size).disabled(__ -> children.isEmpty());
				childIndex = 1;
				window.highlightShowMultiRow(this, element == Core.scene.root ? "ROOT"
				 : ReviewElement.getSimpleName(element.getClass()) + (element.name != null ? ": " + element.name : ""));
				image().growY().left()
				 .update(t -> t.color.set(ReviewElement.focusFrom == this ? ColorFul.color : Color.darkGray));
				defaults().growX();
				add(new LimitTable(t -> {
						/*if (children.isEmpty()) {
							return;
						}*/
					// t.marginLeft(size / 4f);
					Table table1 = new Table();
					Runnable rebuild = () -> {
						table1.clearChildren();
						for (var child : children) {
							if (child == null) continue;
							window.build(child, table1);
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
							image.actions(Actions.rotateTo(-90, duration));
							_cell.setElement(table1);
						} else {
							image.actions(Actions.rotateTo(0, duration));
							_cell.clearElement();
						}
					});
				})).left();
				// Log.info(wrap);
			} else if (element instanceof Image img) {
				childIndex = 0;
				table(p0 -> {
					// Tooltip tooltip = new Tooltip(t -> t.image(((Image) element).getDrawable())) ;
					// tooltip.allowMobile = true;
					// p0.addListener(tooltip);
					p0.table(Window.myPane, p -> {
						try {
							int   size = 32;
							float w    = Math.max(1, element.getWidth());
							float mul  = element.getHeight() / w;
							// float mul    = element.getHeight() / element.getHeight();
							p.add(new Image(img.getDrawable()))
							 .color(element.color)
							 .size(size, size * mul);
						} catch (Throwable e) {
							p.add("空图像").labelAlign(Align.left);
						}
					});
					// 用于补位
					p0.add().growX();
				}).growX().get();
			} else {
				defaults().growX();
				childIndex = 0;
				window.highlightShowMultiRow(this, String.valueOf(element));
			}
			// JSFunc.addStoreButton(wrap, "element", () -> element);
			Element  _elem = getChildren().get(childIndex);
			MenuList list  = copyAsJSMenu(null, () -> element);
			IntUI.addShowMenuListener(_elem, () -> sr(Seq.with(
			 list,
			 ConfirmList.with(Icon.trash, "@clear", "@confirm.remove", () -> element.remove()),
			 MenuList.with(Icon.copy, "@copy.path", () -> {
				 JSFunc.copyText(getPath(element));
			 }),
			 MenuList.with(Icon.fileImage, "@reviewElement.screenshot", () -> {
				 Tools.quietScreenshot(element);
			 }),
			 MenuList.with(Icon.adminSmall, "@settings.debugbounds", () -> JSFunc.toggleDrawPadElem(element)),
			 MenuList.with(Icon.info, "新窗口", () -> new ReviewElementWindow().show(element)),
			 MenuList.with(Icon.info, "@details", () -> JSFunc.showInfo(element))
			)).ifRun(element instanceof Table, seq -> seq.add(
			 MenuList.with(Icon.waves, "cells", () -> {
				 JSFunc.dialog(d -> {
					 d.left().defaults().left();
					 // Table p = new_Table(d);
					 for (var cell : ((Table) element).getCells()) {
						 // p.left();
						 d.table(Tex.pane, t0 -> {
							 t0.add(String.valueOf(cell));
						 });
						 if (cell.isEndRow()) {
							 // p = new_Table(d);
							 d.row();
						 }
					 }
				 });
			 }))).get());
			IntUI.doubleClick(_elem, null, list.run);
			touchable = Touchable.enabled;
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
	private static Table new_Table(Table d) {
		Table p;
		p = d.table().get();
		d.row();
		return p;
	}


	public static class ElementDetailsWindow extends Window implements DisposableInterface {
		Element element;
		public ElementDetailsWindow(Element element) {
			super("", 20, 160, true);
			this.element = element;

			cont.defaults().growX();
			cont.table(setter -> {
				setter.defaults().height(42).growX();
				setter.add(floatSetter("x", () -> "" + element.x, val -> element.x = val)).row();
				setter.add(floatSetter("y", () -> "" + element.y, val -> element.y = val)).row();
				setter.add(floatSetter("width", () -> "" + element.getWidth(), element::setWidth)).row();
				setter.add(floatSetter("height", () -> "" + element.getHeight(), element::setHeight)).row();
			}).growX().row();
			Table table = cont.table().get();
			table.defaults().growX();
			if (element.parent instanceof Table) {
				var cl = ((Table) element.parent).getCell(element);
				table.table(Tex.pane, t -> {
					t.defaults().grow();
					t.add();
					getAdd(t, cl, "padTop");
					t.add().row();
					getAdd(t, cl, "padLeft");
					t.image();
					getAdd(t, cl, "padRight").row();
					t.add();
					getAdd(t, cl, "padBottom");
					t.add();
				}).colspan(2).row();
				table.button("growX", Styles.flatBordert, cl::growX).height(32).growX();
				table.button("growY", Styles.flatBordert, cl::growY).height(32).growX();
			}
		}
		private Cell<Table> getAdd(Table t, Cell cl, String name) {
			return t.add(floatSetter(null, () -> "" + Reflect.get(Cell.class, cl, name), f -> {
				Reflect.set(Cell.class, cl, name, f);
				if (cl.get() != null) cl.get().invalidateHierarchy();
			}));
		}
		public Table floatSetter(String name, Prov<CharSequence> def, Floatc floatc) {
			return new Table(t -> {
				t.defaults().grow();
				if (name != null) t.add(name);
				ModifiedLabel.build(def, Tools::isNum, (field, label) -> {
					if (!field.isValid()) return;
					label.setText(field.getText());
					floatc.get(Tools.asFloat(field.getText()));
				}, 2, t);
			});
		}
	}
}
