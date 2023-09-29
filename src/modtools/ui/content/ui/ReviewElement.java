package modtools.ui.content.ui;

import arc.Core;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.*;
import modtools.IntVars;
import modtools.annotations.builder.*;
import modtools.ui.*;
import modtools.ui.HopeIcons;
import modtools.ui.TopGroup.FocusTask;
import modtools.ui.components.ListDialog.ModifiedLabel;
import modtools.ui.components.*;
import modtools.ui.components.Window.DisposableInterface;
import modtools.ui.components.buttons.FoldedImageButton;
import modtools.ui.components.input.MyLabel;
import modtools.ui.components.limit.LimitTable;
import modtools.ui.content.*;
import modtools.ui.effect.MyDraw;
import modtools.utils.*;
import modtools.utils.MySettings.Data;
import modtools.utils.ui.search.BindCell;

import java.util.regex.*;

import static arc.Core.scene;
import static modtools.ui.Contents.review_element;
import static modtools.ui.IntStyles.*;
import static modtools.ui.IntUI.*;
import static modtools.ui.content.SettingsUI.bool;
import static modtools.utils.Tools.*;

public class ReviewElement extends Content {
	@DataColorFieldInit(needSetting = true)
	public int
	 // 浅绿色
	 padColor        = 0x8C_E9_9A_75,
	 padTextColor    = Color.green.rgba(),
	 marginColor     = Tmp.c1.set(Color.orange).a(0.5f).rgba(),
	 marginTextColor = Pal.accent.rgba(),
	 posLineColor    = Tmp.c1.set(Color.slate).a(0.6f).rgba(),
	 posTextColor = Color.lime.rgba(),
	 sizeTextColor   = Color.magenta.rgba();


	@DataBoolFieldInit(data = "")
	private boolean
	 hoverInfoWindow = true;

	public ReviewElement() {
		super("reviewElement", HopeIcons.codeSmall);
		scene.root.getCaptureListeners().insert(0, new InputListener() {
			public boolean keyDown(InputEvent event, KeyCode keycode) {
				if (Core.input.ctrl() && Core.input.shift() && keycode == KeyCode.c) {
					topGroup.requestSelectElem(null, callback);
					HopeInput.justPressed.clear();
					event.stop();
				}
				return true;
			}
		});
	}

	public static final boolean    hideSelf  = true;
	public static final LabelStyle skyMyFont = new LabelStyle(MyFonts.def, Color.sky);


	public static Element FOCUS;
	/**
	 * focus的来源元素
	 */
	public static Element FOCUS_FROM;
	public static Window  FOCUS_WINDOW;

	public static void addFocusSource(Element source, Prov<Window> windowProv, Prov<Element> focusProv) {
		if (focusProv == null) throw new IllegalArgumentException("focusProv is null.");
		if (windowProv == null) throw new IllegalArgumentException("windowProv is null.");
		source.hovered(() -> {
			FOCUS_FROM = source;
			FOCUS = focusProv.get();
			FOCUS_WINDOW = windowProv.get();
		});
		source.exited(CANCEL_TASK);
	}
	public static final Color focusColor = DEF_FOCUS_COLOR;
	public static final Color maskColor  = DEF_MASK_COLOR;

	public void loadSettings(Data SETTINGS) {
		Contents.settings_ui.add(localizedName(), icon, new Table() {{
			left().defaults().left();
			settingBool(this);
			table(t -> settingColor(t)).grow();
		}});
	}

	/** 代码生成{@link ColorProcessor} */
	public void settingColor(Table t) {}
	// @DataBoolSetting
	public void settingBool(Table t) {
		boolean[] __ = {topGroup.selectInvisible, hoverInfoWindow};
	}



	public void load() {
		loadSettings();
		topGroup.focusOnElement(new MyFocusTask());

		btn.update(() -> btn.setChecked(topGroup.isSelecting()));
		btn.setStyle(Styles.logicTogglet);

		TopGroup.searchBlackList.add(btn);
		TopGroup.classBlackList.add(ReviewElementWindow.class);
	}
	public void drawPadding(Element elem, Vec2 vec2, Table table) {
		/* 如果a = 0就返回 */
		if ((padColor & 0x000000FF) == 0) return;
		Draw.color(padColor);
		Cell<?> cl = table.getCell(elem);
		if (cl == null) {
			return;
		}
		float padLeft   = Reflect.get(Cell.class, cl, "padLeft");
		float padTop    = Reflect.get(Cell.class, cl, "padTop");
		float padBottom = Reflect.get(Cell.class, cl, "padBottom");
		float padRight  = Reflect.get(Cell.class, cl, "padRight");

		drawMarginOrPad(vec2, elem, true, padLeft, padTop, padRight, padBottom);
	}

	public void drawMargin(Vec2 vec2, Table table) {
		if ((marginColor & 0x000000FF) == 0) return;
		Draw.color(marginColor);

		drawMarginOrPad(vec2, table, false,
		 table.getMarginLeft(), table.getMarginTop(),
		 table.getMarginRight(), table.getMarginBottom());
	}
	/** @param pad true respect 外边距 */
	private void drawMarginOrPad(
	 Vec2 vec2, Element elem, boolean pad,
	 float left, float top, float right, float bottom) {

		if (pad) {
			left *= -1;
			top *= -1;
			right *= -1;
			bottom *= -1;
		}
		Color color = pad ? Tmp.c1.set(padTextColor) : Tmp.c1.set(marginTextColor);
		float mul   = pad ? -1 : 1;
		// 左边 left
		if (left != 0) {
			Fill.crect(vec2.x, vec2.y, left, elem.getHeight());
			MyDraw.drawText(fixed(left * mul),
			 vec2.x + left / 2f,
			 vec2.y + (MyDraw.fontHeight() + elem.getHeight()) / 2f, color);
		}

		// 底部 bottom
		if (bottom != 0) {
			Fill.crect(vec2.x, vec2.y, elem.getWidth(), bottom);
			MyDraw.drawText(fixed(bottom * mul),
			 vec2.x + elem.getWidth() / 2f,
			 vec2.y + bottom, color);
		}

		// 顶部 top
		if (top != 0) {
			Fill.crect(vec2.x, vec2.y + elem.getHeight(), elem.getWidth(), -top);
			MyDraw.drawText(fixed(top * mul),
			 vec2.x + elem.getWidth() / 2f,
			 vec2.y + elem.getHeight() - top / 2f, color);
		}

		// 右边 right
		if (right != 0) {
			Fill.crect(vec2.x + elem.getWidth(), vec2.y, -right, elem.getHeight());
			MyDraw.drawText(fixed(right * mul),
			 vec2.x + elem.getWidth() - left / 2f,
			 vec2.y + (MyDraw.fontHeight() + elem.getHeight()) / 2f, color);
		}
	}
	/** 从元素到hover的元素的连线 */
	public void drawLine() {
		if (FOCUS == null) return;

		Vec2 vec2  = FOCUS.localToStageCoordinates(Tmp.v2.set(0, 0));
		Vec2 mouse = Core.input.mouse();
		Draw.color(ColorFul.color);
		Lines.stroke(4f);
		Lines.line(mouse.x, mouse.y, vec2.x + FOCUS.getWidth() / 2f, vec2.y + FOCUS.getHeight() / 2f);
	}

	public static final Cons<Element> callback = selected -> new ReviewElementWindow().show(selected);
	public void build() {
		topGroup.requestSelectElem(null, callback);
	}


	public static final Runnable CANCEL_TASK = () -> {
		FOCUS = null;
		FOCUS_WINDOW = null;
		FOCUS_FROM = null;
	};

	public static class ReviewElementWindow extends Window implements DisposableInterface {
		Table   pane    = new LimitTable() {};
		Element element = null;
		Pattern pattern;

		public ReviewElementWindow() {
			super(review_element.localizedName(), 20, 160, true);
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
						 /* 左上角对齐 */
						 window.pattern = pattern;
						 window.show(element.parent);
						 window.shown(() -> {
							 window.setSize(width, height);
							 window.setPosition(x, getY(Align.topLeft), Align.topLeft);
						 });
						 hide();
					 };
					 if (element.parent == scene.root) {
						 Vec2 vec2 = ElementUtils.getAbsPos(bs[0]);
						 IntUI.showConfirm("@reviewElement.confirm.root", go).setPosition(vec2);
					 } else go.run();
				 })
				 .disabled(b -> element == null || element.parent == null)
				 .width(120).get();
				t.button(Icon.copy, clearNonei, () -> {
					var window = new ReviewElementWindow();
					window.pattern = pattern;
					window.show(element);
					window.shown(() -> window.setSize(width, height));
				}).padLeft(4f).padRight(4f);
				t.button(Icon.refresh, clearNonei, () -> rebuild(element, pattern)).padLeft(4f).padRight(4f);
				t.table(search -> {
					search.image(Icon.zoom);
					search.field("", str -> rebuild(element, str)).growX();
				}).growX().padLeft(2f);
			}).growX().row();

			cont.add(new ScrollPane(pane) {
				public String toString() {
					if (hideSelf) return name;
					return super.toString();
				}
			}).grow().minHeight(90);

			/* update(() -> {
				if (focusWindow instanceof ReviewElementWindow && !CANCEL_TASK.isScheduled()) {
					Timer.schedule(CANCEL_TASK, Time.delta / 60f);
				}
			}); */
		}

		public void rebuild(Element element, String text) {
			pane.clearChildren();

			if (element == null) return;
			Pattern pattern = PatternUtils.compileRegExpCatch(text);
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


		public void highlightShowMultiRow(Table table, String text) {
			addMultiRowWithPos(table, text, null);
		}
		/** 结构： Label，Image（下划线） */
		public void addMultiRowWithPos(Table table, String text, Prov<Vec2> pos) {
			if (pattern == null) {
				table.table(t -> {
					t.left().defaults().left();
					makePosLabel(t, pos);
					t.add(new MyLabel(text, MOMO_LabelStyle)).growX().left().color(Pal.accent);
				}).growX().left().row();
				table.image().color(JSFunc.c_underline).growX().colspan(2).row();
				return;
			}
			table.table(t -> {
				t.left().defaults().left();
				makePosLabel(t, pos);
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

	static void makePosLabel(Table t, Prov<Vec2> pos) {
		if (pos != null) t.label(new PositionProv(pos))
		 .style(MOMO_LabelStyle).color(Color.lightGray)
		 .fontScale(0.85f).padLeft(4f).padRight(4f);
	}

	private static class MyWrapTable extends ChildrenFirstTable {
		public MyWrapTable(ReviewElementWindow window, Element element) {
			/* 用于下面的侦听器  */
			int childIndex;
			/* 用于添加侦听器 */
			if (element instanceof Group group) {
				/* 占位符 */
				var button   = new FoldedImageButton(false);
				int size     = 32;
				var children = group.getChildren();
				add(button).size(size).disabled(__ -> children.isEmpty());
				childIndex = 1;
				window.addMultiRowWithPos(this,
				 getElementName(element),
				 () -> Tmp.v1.set(element.x, element.y));
				image().growY().left().update(
				 t -> t.color.set(FOCUS_FROM == this ? ColorFul.color : Color.darkGray)
				);
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
					int[] lastChildrenSize = {children.size};

					button.table = table1;
					button.fireCheck(!children.isEmpty() && children.size < 20);
					button.cell = t.add(table1).grow();
					button.rebuild = () -> {
						if (lastChildrenSize[0] == children.size) {return;}
						rebuild.run();
					};
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
							 .update(t -> t.setColor(element.color))
							 .size(size, size * mul);
						} catch (Throwable e) {
							p.add("空图像").labelAlign(Align.left);
						}
					});
					Prov<Vec2> prov = () -> Tmp.v1.set(element.x, element.y);
					makePosLabel(p0, prov);
					// 用于补位
					p0.add().growX();
				}).growX().get();
			} else {
				defaults().growX();
				childIndex = 0;
				window.addMultiRowWithPos(this, String.valueOf(element),
				 () -> Tmp.v1.set(element.x, element.y));
			}
			// JSFunc.addStoreButton(wrap, "element", () -> element);
			Element  window_elem = getChildren().get(childIndex);
			Runnable copy        = storeRun(() -> element);
			IntUI.addShowMenuListener(window_elem, () -> Sr(Seq.with(
			 copyAsJSMenu(null, copy),
			 ConfirmList.with(Icon.trashSmall, "@clear", "@confirm.remove", () -> element.remove()),
			 MenuList.with(Icon.copySmall, "@copy.path", () -> {
				 JSFunc.copyText(getPath(element));
			 }),
			 MenuList.with(Icon.fileImageSmall, "@reviewElement.screenshot", () -> {
				 ElementUtils.quietScreenshot(element);
			 }),
			 MenuList.with(Icon.adminSmall, "@settings.debugbounds", () -> JSFunc.toggleDrawPadElem(element)),
			 MenuList.with(Icon.infoSmall, "新窗口", () -> new ReviewElementWindow().show(element)),
			 MenuList.with(Icon.infoSmall, "@details", () -> JSFunc.showInfo(element)),
			 FoldedList.withf(Icon.boxSmall, "exec", () -> Seq.with(
				MenuList.with(Icon.boxSmall, "invalidate", element::invalidate),
				MenuList.with(Icon.boxSmall, "invalidateHierarchy", element::invalidateHierarchy),
				MenuList.with(Icon.boxSmall, "layout", element::layout),
				MenuList.with(Icon.boxSmall, "pack", element::pack)
			 )),
			 ValueLabel.newElementDetailsList(element)
			)).ifRun(element instanceof Table, seq -> seq.add(
				MenuList.with(Icon.waves, "cells", () -> {
					JSFunc.dialog(d -> {
						d.left().defaults().left();
						for (var cell : ((Table) element).getCells()) {
							d.table(Tex.pane, t0 -> {
								t0.add(new ValueLabel(cell, Cell.class, null, null));
							});
							if (cell.isEndRow()) {
								d.row();
							}
						}
					});
				})))
			 .ifRun(element.parent instanceof Table, seq -> seq.add(
				DisabledList.withd(Icon.waves, "this cell", () -> !(element.parent instanceof Table), () -> {
					new CellDetailsWindow(((Table) element.parent).getCell(element)).show();
				}))).get());
			IntUI.doubleClick(window_elem, null, copy);
			touchable = Touchable.enabled;

			update(() -> background(FOCUS_FROM == this ? Styles.flatDown : Styles.none));
			addFocusSource(this, () -> window, () -> element);
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
	private static String getElementName(Element element) {
		return element == scene.root ? "ROOT"
		 : ReviewElement.getSimpleName(element.getClass())
			 + (element.name != null ? ": " + element.name : "");
	}


	public static class CellDetailsWindow extends Window implements DisposableInterface {
		Cell<?> cl;
		public CellDetailsWindow(Cell<?> cell) {
			super("cell");
			this.cl = cell;

			cont.table(Tex.pane, t -> {
				t.defaults().grow();
				t.add();
				getAdd(t, cell, "padTop");
				t.add().row();
				getAdd(t, cell, "padLeft");
				ValueLabel label = new ValueLabel(cell.get(), Element.class, null, null);
				label.enableUpdate = false;
				label.update(() -> label.setVal(cell.get()));
				Label   placeholder     = new MyLabel("<VALUE>", MOMO_LabelStyle);
				Cell<?> placeholderCell = t.add(placeholder);
				placeholder.clicked(() -> placeholderCell.setElement(label));
				label.clicked(() -> placeholderCell.setElement(placeholder));

				getAdd(t, cell, "padRight").row();
				t.add();
				getAdd(t, cell, "padBottom");
				t.add();
			}).colspan(2).row();
			cont.defaults().height(32).growX();
			cont.defaults().colspan(2);
			getAddWithName(cont, cell, "minWidth").row();
			getAddWithName(cont, cell, "minHeight").row();
			getAddWithName(cont, cell, "maxWidth").row();
			getAddWithName(cont, cell, "maxHeight").row();
			cont.defaults().colspan(1);
			checkField(cont, cell, "fillX", float.class);
			checkField(cont, cell, "fillY", float.class);
			cont.row();
			checkField(cont, cell, "expandX", int.class);
			checkField(cont, cell, "expandY", int.class);
			cont.row();
			checkField(cont, cell, "uniformX", boolean.class);
			checkField(cont, cell, "uniformY", boolean.class);
			cont.row();
			cont.button("growX", Styles.flatBordert, cell::growX);
			cont.button("growY", Styles.flatBordert, cell::growY);
			cont.row();
			checkField(cont, cell, "endRow", boolean.class).colspan(2);

			addFocusSource(this, () -> this, cell::get);
		}
	}
	/* private static <T> void field(Table cont, Cell<?> cell, String key, TextFieldValidator validator,
																Func<String, T> func) {
		cont.table(t -> {
			t.add(key);
			ModifiedLabel.build(() -> String.valueOf(Reflect.get(Cell.class, cell, key)),
			 validator, (field, label) -> {
				 if (!field.isValid()) return;
				 Reflect.set(Cell.class, cell, key, func.get(field.getText()));
				 label.setText(field.getText());
			 }, 2, t);
		});
	} */
	private static Cell<CheckBox> checkField(Table cont, Cell<?> obj, String key, Class<?> valueType) {
		return checkField(cont, Cell.class, obj, key, valueType);
	}

	private static <T> Cell<CheckBox> checkField(Table cont, Class<? extends T> ctype, T obj, String key,
																							 Class<?> valueType) {
		return cont.check(key, getChecked(ctype, obj, key), b -> {
			Reflect.set(ctype, obj, key, valueType == Boolean.TYPE ? b : b ? 1 : 0);
		}).checked(__ -> getChecked(ctype, obj, key));
	}
	private static <T> Boolean getChecked(Class<? extends T> ctype, T obj, String key) {
		return Sr(Reflect.get(ctype, obj, key))
		 .reset(t -> t instanceof Boolean ? (Boolean) t :
			t instanceof Number n && n.intValue() == 1)
		 .get();
	}


	public static class ElementDetailsWindow extends Window implements DisposableInterface {
		Element element;

		public ElementDetailsWindow(Element element) {
			super("", 20, 160, true);
			addFocusSource(this, () -> this, () -> element);


			show();
			this.element = element;

			cont.defaults().growX();
			cont.table(setter -> {
				setter.defaults().height(42).growX();
				setter.add(floatSetter("x", () -> fixed(element.x), val -> element.x = val)).row();
				setter.add(floatSetter("y", () -> fixed(element.y), val -> element.y = val)).row();
				setter.add(floatSetter("width", () -> fixed(element.getWidth()), element::setWidth)).row();
				setter.add(floatSetter("height", () -> fixed(element.getHeight()), element::setHeight)).row();
				setter.add(floatSetter("prefWidth", () -> fixed(element.getPrefWidth()), null)).row();
				setter.add(floatSetter("preHeight", () -> fixed(element.getPrefHeight()), null)).row();
				setter.add(floatSetter("rot", () -> fixed(element.getRotation()), element::setRotation)).row();
			}).growX().row();
			Table table = cont.table().get();
			table.defaults().growX();
			l:
			if (element.parent instanceof Table) {
				var cl = ((Table) element.parent).getCell(element);
				if (cl == null) break l;
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
				table.defaults().height(32).growX();
				table.button("growX", Styles.flatBordert, cl::growX);
				table.button("growY", Styles.flatBordert, cl::growY);
				table.row();
			}

			checkField(table, Element.class, element, "fillParent", boolean.class);
			checkField(table, Element.class, element, "visible", boolean.class).row();
			if (element instanceof Group) checkField(table, Group.class, element, "transform", boolean.class).row();

			cont.row().defaults().height(32).growX();
			cont.button("invalidate", Styles.flatBordert, element::invalidate).row();
			cont.button("invalidateHierarchy", Styles.flatBordert, element::invalidateHierarchy).row();
			cont.button("layout", Styles.flatBordert, element::layout).row();
			cont.button("pack", Styles.flatBordert, element::pack).row();
		}
	}
	private static Cell<Table> getAdd(Table t, Cell cell, String name) {
		return t.add(floatSetter(null, () -> "" + Reflect.get(Cell.class, cell, name), f -> {
			Reflect.set(Cell.class, cell, name, f);
			if (cell.get() != null) cell.get().invalidateHierarchy();
		}));
	}
	private static Cell<Table> getAddWithName(Table t, Cell cell, String name) {
		return t.add(floatSetter("[lightgray]" + name + ": ", () -> "" + Reflect.get(Cell.class, cell, name), f -> {
			Reflect.set(Cell.class, cell, name, f);
			if (cell.get() != null) cell.get().invalidateHierarchy();
		}));
	}
	public static Table floatSetter(String name, Prov<CharSequence> def, Floatc floatc) {
		return new Table(t -> {
			if (name != null) t.add(name).color(Color.gray).padRight(8f);
			t.defaults().growX();
			if (floatc == null) {
				t.label(def);
				return;
			}
			ModifiedLabel.build(def, Strings::canParseFloat, (field, label) -> {
				if (!field.isValid()) return;
				label.setText(field.getText());
				floatc.get(Strings.parseFloat(field.getText()));
			}, 2, t, TextField::new);
		});
	}

	static String fixed(float value) {
		return Strings.autoFixed(value, 1);
	}
	private class MyFocusTask extends FocusTask {
		{drawSlightly = true;}

		public MyFocusTask() {super(ReviewElement.maskColor, ReviewElement.focusColor);}

		/** 清除elemDraw */
		public void elemDraw() {}
		public void beforeDraw(Element drawer) {
			if (drawer == FOCUS_WINDOW && FOCUS != null) {
				drawFocus(FOCUS);
			}
		}
		public void drawFocus(Element elem, Vec2 vec2) {
			super.drawFocus(elem, vec2);
			posLine:
			{
				if ((posLineColor & 0x000000FF) == 0) break posLine;
				Lines.stroke(4);
				Draw.color(posLineColor);
				// x: 0 -> x
				Lines.line(0, vec2.y, vec2.x, vec2.y);
				// y: 0 -> y
				Lines.line(vec2.x, 0, vec2.x, vec2.y);
			}
			posText:
			{
				if ((posTextColor & 0x000000FF) == 0) break posText;
				// x: 0 -> x
				MyDraw.drawText(fixed(vec2.x),
				 vec2.x / 2f, vec2.y, Tmp.c1.set(posTextColor));
				// y: 0 -> y
				MyDraw.drawText(fixed(vec2.y),
				 vec2.x, vec2.y / 2f, Tmp.c1.set(posTextColor));
			}

			sizeText:
			{
				Color color = Tmp.c1.set(sizeTextColor);
				if (color.a == 0) break sizeText;
				float w = elem.getWidth();
				float h = elem.getHeight();
				// width
				boolean flipX = vec2.x < 32, flipY = vec2.y < 32;
				MyDraw.drawText(fixed(w),
				 vec2.x + w / 2f,
				 (flipY ? Core.graphics.getHeight() - MyDraw.fontHeight() : MyDraw.fontHeight()),
				 color, Align.center);

				// height
				MyDraw.drawText(fixed(h),
				 flipX ? Core.graphics.getWidth() : 0,
				 vec2.y + (h + MyDraw.fontHeight()) / 2f,
				 color, flipX ? Align.right : Align.left);
			}

			if (elem instanceof Table table) {
				drawMargin(vec2, table);
			}

			if (elem.parent instanceof Table table) {
				drawMargin(table.localToStageCoordinates(Tmp.v1.set(0, 0)), table);

				drawPadding(elem, vec2, table);
			}

			if (!hoverInfoWindow) return;
			table.nameLabel.setText(getElementName(elem));
			table.sizeLabel.setText(fixed(elem.getWidth()) + "×" + fixed(elem.getHeight()));
			table.colorContainer.setColorValue(elem.color);
			table.colorLabel.setText("" + elem.color);
			table.rotation(elem.rotation);
			table.translation(elem.translation);

			displayDetails(elem, vec2);
		}

		// ---------------------
		private void displayDetails(Element elem, Vec2 vec2) {
			table.colorContainer.invalidate();
			table.colorContainer.layout();
			table.colorLabel.invalidate();
			table.colorLabel.layout();
			table.invalidate();
			table.act(1);
			table.getPrefWidth();
			table.layout();
			table.bottom().left();
			float x = vec2.x;
			if (x + table.getPrefWidth() > Core.graphics.getWidth()) {
				x = Core.graphics.getWidth();
				table.right();
			}
			if (x < 0) {
				x = 0;
				table.left();
			}
			float y = vec2.y + elem.getHeight();
			if (y + table.getPrefHeight() > Core.graphics.getHeight()) {
				y = vec2.y;
				table.top();
			}
			if (y - table.getPrefHeight() < 0) {
				table.bottom();
				if (y < vec2.y && y + table.getPrefHeight() > y
						&& y + table.getPrefHeight() < Core.graphics.getHeight()) {
					y = vec2.y + elem.getHeight();
				} else {
					y = 0;
				}
			}
			table.setPosition(x, y);
			table.draw();
		}
		final InfoDetails table = new InfoDetails();
		class InfoDetails extends Table {
			Label nameLabel   = new Label(""), sizeLabel = new Label("", MOMO_LabelStyle),
			 transformLabel   = new Label(""),
			 colorLabel       = new Label(""),
			 rotationLabel    = new Label("0"),
			 translationLabel = new Label("");
			ColorContainer colorContainer = new ColorContainer(Color.white);

			BindCell rotCell, translationCell;
			void rotation(float rotation) {
				if (rotation % 360 == 0) {
					rotCell.remove();
					return;
				}
				rotCell.build();
				rotationLabel.setText(fixed(rotation));
			}
			void translation(Vec2 translation) {
				if (Mathf.zero(translation.x) && Mathf.zero(translation.y)) {
					translationCell.remove();
					return;
				}
				translationCell.build();
				translationLabel.setText(
				 fixed(translation.x) + "×"
				 + fixed(translation.y));
			}
			{
				margin(4, 4, 4, 4);
				nameLabel.setFontScale(0.7f);
				sizeLabel.setFontScale(0.7f);
				colorLabel.setFontScale(0.6f);
				rotationLabel.setFontScale(0.6f);
				translationLabel.setFontScale(0.6f);
				table(Tex.pane, t -> {
					t.table(top -> {
						top.add(nameLabel);
						top.add(sizeLabel).padLeft(10f)
						 .growX().right().labelAlign(Align.right).color(Color.lightGray);
					}).growX();
					t.row().table(col -> {
						col.add("Color").fontScale(0.7f).color(Color.lightGray).growX().padRight(6f);
						col.add(colorContainer).size(16);
						col.add(colorLabel).row();
					}).growX();
					rotCell = new BindCell(t.row().table(col -> {
						col.add("Rotation").fontScale(0.7f).color(Color.lightGray).growX().padRight(6f);
						col.add(rotationLabel).row();
					}).growX());
					translationCell = new BindCell(t.row().table(col -> {
						col.add("Translation").fontScale(0.7f).color(Color.lightGray).growX().padRight(6f);
						col.add(translationLabel).row();
					}).growX());
				});
			}
		}
		public void endDraw() {
			if (topGroup.isSelecting()) super.endDraw();
			drawLine();
			elem = topGroup.getSelected();
			if (elem != null) super.elemDraw();
		}
	}
}
