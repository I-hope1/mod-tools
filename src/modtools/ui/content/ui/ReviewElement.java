package modtools.ui.content.ui;

import arc.Core;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.style.Style;
import arc.scene.ui.*;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.layout.*;
import arc.struct.Seq;
import arc.util.*;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.annotations.OptimizeReflect;
import modtools.annotations.builder.*;
import modtools.ui.*;
import modtools.ui.HopeIcons;
import modtools.ui.TopGroup.FocusTask;
import modtools.ui.components.*;
import modtools.ui.components.Window.IDisposable;
import modtools.ui.components.buttons.FoldedImageButton;
import modtools.ui.components.input.*;
import modtools.ui.components.limit.LimitTable;
import modtools.ui.components.review.CellDetailsWindow;
import modtools.ui.components.utils.*;
import modtools.ui.components.windows.ListDialog.ModifiedLabel;
import modtools.ui.content.Content;
import modtools.ui.control.HopeInput;
import modtools.ui.effect.MyDraw;
import modtools.ui.menu.*;
import modtools.utils.*;
import modtools.utils.JSFunc.JColor;
import modtools.utils.MySettings.Data;
import modtools.utils.jsfunc.*;
import modtools.utils.ui.search.BindCell;

import java.lang.reflect.InvocationTargetException;
import java.util.regex.*;

import static arc.Core.scene;
import static modtools.ui.Contents.review_element;
import static modtools.ui.HopeStyles.defaultLabel;
import static modtools.ui.IntUI.*;
import static modtools.utils.Tools.*;
import static modtools.utils.ui.FormatHelper.*;

/** It should be `InspectElement`, but it's too late.  */
public class ReviewElement extends Content {
	@DataColorFieldInit(data = "", needSetting = true)
	public int
	 // 浅绿色
	 padColor        = 0x8C_E9_9A_75,
	 padTextColor    = Color.green.rgba(),
	 marginColor     = Tmp.c1.set(Color.orange).a(0.5f).rgba(),
	 marginTextColor = Pal.accent.rgba(),
	 posLineColor    = Tmp.c1.set(Color.slate).a(0.6f).rgba(),
	 posTextColor    = Color.lime.rgba(),
	 sizeTextColor   = Color.magenta.rgba();


	@DataBoolFieldInit(data = "")
	private boolean
	 hoverInfoWindow = true;

	public ReviewElement() {
		super("reviewElement", HopeIcons.codeSmall);
		scene.root.getCaptureListeners().insert(0, new InputListener() {
			public boolean keyDown(InputEvent event, KeyCode keycode) {
				if (Core.input.ctrl() && Core.input.shift() && keycode == KeyCode.c) {
					build();
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
	@DataBoolSetting
	public void settingBool(Table t) {
		boolean[] __ = {topGroup.selectInvisible, hoverInfoWindow};
	}


	public void load() {
		loadSettings();
		ReviewFocusTask task = new ReviewFocusTask();
		topGroup.focusOnElement(task);

		btn.update(() -> btn.setChecked(task.isSelecting()));
		btn.setStyle(HopeStyles.hope_clearTogglet);

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
		@OptimizeReflect
		float padLeft = Reflect.get(Cell.class, cl, "padLeft"),
		 padTop = Reflect.get(Cell.class, cl, "padTop"),
		 padBottom = Reflect.get(Cell.class, cl, "padBottom"),
		 padRight = Reflect.get(Cell.class, cl, "padRight");

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
		left = TopGroup.clamp(vec2, left, true);
		top = TopGroup.clamp(vec2, top, false);
		right = TopGroup.clamp(vec2, right, true);
		bottom = TopGroup.clamp(vec2, bottom, false);
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
		if (topGroup.isSelecting()) topGroup.resetSelectElem();
		else topGroup.requestSelectElem(null, callback);
	}


	public static final Runnable CANCEL_TASK = () -> {
		FOCUS = null;
		FOCUS_WINDOW = null;
		FOCUS_FROM = null;
	};

	public static class ReviewElementWindow extends Window implements IDisposable {
		Table   pane = new LimitTable() {};
		Element element;
		Pattern pattern;

		public ReviewElementWindow() {
			super(review_element.localizedName(), 20, 160, true);
			getCell(cont).maxWidth(Core.graphics.getWidth());

			name = "ReviewElementWindow";

			//			addCloseButton();
			pane.left().defaults().left();
			cont.table(t -> {
				Button[] bs = {null};
				bs[0] = t.button("@reviewElement.parent", Icon.upSmall, Styles.flatBordert, () -> {
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
						 Vec2 vec2 = ElementUtils.getAbsolutePos(bs[0]);
						 IntUI.showConfirm("@reviewElement.confirm.root", go).setPosition(vec2);
					 } else go.run();
				 })
				 .with(b -> {
					 t.update(() -> b.setDisabled(element == null || element.parent == null));
					 b.getLabel().setFontScale(0.9f);
				 })
				 .size(130, 35).get();
				t.button(Icon.copySmall, HopeStyles.clearNonei, 28, () -> {
					 var window = new ReviewElementWindow();
					 window.pattern = pattern;
					 window.show(element);
					 window.shown(() -> window.setSize(width, height));
				 })
				 .size(35)
				 .padLeft(4f).padRight(4f);
				t.button(Icon.refreshSmall, HopeStyles.clearNonei, 28, () -> rebuild(element, pattern))
				 .size(35)
				 .padLeft(4f).padRight(4f);
				t.table(search -> {
					search.image(Icon.zoomSmall);
					search.field("", str -> rebuild(element, str))
					 .with(f -> f.setMessageText("@players.search"))
					 .growX();
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
					t.add(new MyLabel(text, defaultLabel)).growX().left().color(Pal.accent);
				}).growX().left().row();
				table.image().color(Tmp.c1.set(JColor.c_underline)).growX().colspan(2).row();
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
			table.image().color(Tmp.c1.set(JColor.c_underline)).growX().colspan(2).row();
		}

		public void highlightShow(Table table, Pattern pattern, String text) {
			if (pattern == null) {
				table.add(text, defaultLabel).color(Pal.accent);
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
					t.add(text.substring(lastIndex), defaultLabel).color(Pal.accent);
			});
		}
		public void build(Element element, Table table) {
			if (element == null) throw new IllegalArgumentException("element is null");
			if (hideSelf && element instanceof ReviewElementWindow) {
				table.add("----" + name + "-----", defaultLabel).row();
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
			if (isShown()) return;
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

	static void makePosLabel(Table t, Prov<Vec2> pos) {
		if (pos != null) t.label(new PositionProv(pos))
		 .style(defaultLabel).color(Color.lightGray)
		 .fontScale(0.7f).padLeft(4f).padRight(4f);
	}

	private static class MyWrapTable extends ChildrenFirstTable {
		public MyWrapTable(ReviewElementWindow window, Element element) {
			/* 用于下面的侦听器  */
			int childIndex;
			/* 用于添加侦听器 */
			if (element instanceof Group group) {
				/* 占位符 */
				var button   = new FoldedImageButton(true);
				int size     = 32;
				var children = group.getChildren();
				add(button).size(size).disabled(__ -> children.isEmpty());
				childIndex = 1;
				window.addMultiRowWithPos(this,
				 ElementUtils.getElementName(element),
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
					if (children.size < 20) rebuild.run();
					int[] lastChildrenSize = {children.size < 20 ? children.size : -1};

					button.fireCheck(!children.isEmpty() && children.size < 20);
					button.setContainer(t.add(table1).grow());
					button.rebuild = () -> {
						if (lastChildrenSize[0] == children.size) return;
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
					Prov<Vec2> prov = () -> Tmp.v1.set(element.x, element.y);
					makePosLabel(p0, prov);
					p0.table(h -> h.left().table(Window.myPane, p -> {
						try {
							int   size = 28;
							float w    = Math.max(1, element.getWidth());
							float mul  = element.getHeight() / w;
							// float mul    = element.getHeight() / element.getHeight();
							p.add(new Image(img.getDrawable()))
							 .update(t -> t.setColor(element.color))
							 .size(size, size * mul);
						} catch (Throwable e) {
							p.add("空图像").labelAlign(Align.left);
						}
					})).growX().touchable(Touchable.enabled);
					// 用于补位
					p0.add();
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
			IntUI.addShowMenuListenerp(window_elem, () -> Sr(Seq.with(
			 copyAsJSMenu(null, copy),
			 ConfirmList.with(Icon.trashSmall, "@clear", "@confirm.remove", () -> {
				 remove();
				 element.remove();
			 }),
			 MenuList.with(Icon.copySmall, "@copy.path", () -> {
				 JSFunc.copyText(getPath(element));
			 }),
			 MenuList.with(Icon.fileImageSmall, "@reviewElement.screenshot", () -> {
				 ElementUtils.quietScreenshot(element);
			 }),
			 MenuList.with(Icon.adminSmall, "@settings.debugbounds", () -> REVIEW_ELEMENT.toggleDrawPadElem(element)),
			 MenuList.with(Icon.copySmall, "New Window", () -> new ReviewElementWindow().show(element)),
			 MenuList.with(Icon.infoSmall, "@details", () -> INFO_DIALOG.showInfo(element)),
			 FoldedList.withf(Icon.boxSmall, "Exec", () -> Seq.with(
				MenuList.with(Icon.boxSmall, "Invalidate", element::invalidate),
				MenuList.with(Icon.boxSmall, "InvalidateHierarchy", element::invalidateHierarchy),
				MenuList.with(Icon.boxSmall, "Layout", element::layout),
				MenuList.with(Icon.boxSmall, "Pack", element::pack),
				MenuList.with(Icon.boxSmall, "Validate", element::validate),
				MenuList.with(Icon.boxSmall, "Keep in stage", element::keepInStage),
				MenuList.with(Icon.boxSmall, "To Front", element::toFront),
				MenuList.with(Icon.boxSmall, "To Back", element::toBack)
			 )),
			 ValueLabel.newElementDetailsList(element)
			))
			 .ifRun(element instanceof Table, seq -> seq.add(
				MenuList.with(Icon.waves, "Cells", () -> {
					INFO_DIALOG.dialog(d -> {
						d.left().defaults().left();
						for (var cell : ((Table) element).getCells()) {
							d.table(Tex.pane, t0 -> {
								t0.add(new PlainValueLabel<>(Cell.class, () -> cell));
							});
							if (cell.isEndRow()) d.row();
						}
					});
				})))
			 .ifRun(element.parent instanceof Table, seq -> seq.add(
				DisabledList.withd(Icon.waves, "This Cell",
				 () -> !(element.parent instanceof Table && ((Table) element.parent).getCell(element) != null), () -> {
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

	public static Table floatSetter(String name, Prov<CharSequence> def, Floatc floatc) {
		return new Table(t -> {
			if (name != null) t.add(name).color(Pal.accent).fontScale(0.7f).labelAlign(Align.topLeft).growY().padRight(8f);
			t.defaults().grow();
			if (floatc == null) {
				t.label(def);
				return;
			}
			ModifiedLabel.build(def, NumberHelper::isFloat, (field, label) -> {
				if (!field.isValid()) return;
				label.setText(field.getText());
				floatc.get(NumberHelper.asFloat(field.getText()));
			}, t, TextField::new);
		});
	}

	@OptimizeReflect
	static class InfoDetails extends Table {
		public static final float keyScale   = 0.7f;
		public static final float valueScale = 0.6f;
		Label nameLabel = new NoMarkupLabel(0.75f),
		 sizeLabel      = new NoMarkupLabel(0.7f),
		 touchableLabel = new NoMarkupLabel(valueScale),

		// transformLabel    = new MyLabel(""),
		colorLabel        = new NoMarkupLabel(valueScale),
		 rotationLabel    = new NoMarkupLabel(valueScale),
		 translationLabel = new NoMarkupLabel(valueScale),
		 styleLabel       = new NoMarkupLabel(valueScale),

		colspanLabel  = new NoMarkupLabel(valueScale),
		 minSizeLabel = new Label(""), maxSizeLabel = new Label("");
		ColorContainer colorContainer = new ColorContainer(Color.white);

		BindCell rotCell, translationCell, styleCell,
		 cellCell,
		 colspanCell, minSizeCell, maxSizeCell;


		void color(Color color) {
			colorContainer.setColorValue(color);
			colorLabel.setText(color.toString().toUpperCase());
		}
		void rotation(float rotation) {
			if (rotCell.toggle1(rotation % 360 != 0))
				rotationLabel.setText(fixed(rotation));
		}
		void translation(Vec2 translation) {
			if (translationCell.toggle1(!Mathf.zero(translation.x) || !Mathf.zero(translation.y)))
				translationLabel.setText(fixed(translation.x) + " × " + fixed(translation.y));
		}
		void style(Element element) {
			try {
				Style style = (Style) element.getClass().getMethod("getStyle", (Class<?>[]) null).invoke(element, (Object[]) null);
				if (styleCell.toggle1(style != null && ShowUIList.styleKeyMap.containsKey(style)))
					styleLabel.setText(ShowUIList.styleKeyMap.get(style));
			} catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
				styleCell.remove();
			}
		}
		void colspan(Cell<?> cell) {
			if (cell == null) {
				colspanCell.remove();
				return;
			}
			@OptimizeReflect
			int colspan = Reflect.get(Cell.class, cell, "colspan");
			if (colspanCell.toggle1(colspan != 1))
				colspanLabel.setText("" + colspan);
		}
		void minSize(Cell<?> cell) {
			if (cell == null) {
				minSizeCell.remove();
				return;
			}
			@OptimizeReflect
			float minWidth = Reflect.get(Cell.class, cell, "minWidth"),
			 minHeight = Reflect.get(Cell.class, cell, "minHeight");
			if (minSizeCell.toggle1(minWidth != unset || minHeight != unset))
				minSizeLabel.setText(sizeText(minWidth, minHeight));
		}
		void maxSize(Cell<?> cell) {
			if (cell == null) {
				maxSizeCell.remove();
				return;
			}
			@OptimizeReflect
			float maxWidth = Reflect.get(Cell.class, cell, "maxWidth"),
			 maxHeight = Reflect.get(Cell.class, cell, "maxHeight");
			if (maxSizeCell.toggle1(maxWidth != unset || maxHeight != unset))
				maxSizeLabel.setText(sizeText(maxWidth, maxHeight));
		}

		private static String sizeText(float w, float h) {
			return fixedUnlessUnset(w / Scl.scl()) + "[accent]×[]" + fixedUnlessUnset(h / Scl.scl());
		}

		{
			margin(4, 4, 4, 4);
			touchableLabel.setColor(Color.acid);
			minSizeLabel.setFontScale(valueScale);
			maxSizeLabel.setFontScale(valueScale);
			table(Tex.pane, this::build);
		}

		public static final float padRight = 8f;
		Table cells_ = new Table(c -> {
			c.row().add("Cell").color(Pal.accent).left().fontScale(0.73f).padLeft(-2f);
			c.image().color(Tmp.c1.set(Color.orange).lerp(Color.lightGray, 0.9f).a(0.3f)).padLeft(padRight / 2f).padRight(padRight / 2f).growX();
			c.defaults().colspan(2);
		});
		private void build(Table t) {
			t.table(top -> {
				top.add(nameLabel).color(Color.violet).padLeft(-4f);
				top.add(sizeLabel).padLeft(10f)
				 .growX().right().labelAlign(Align.right).color(Color.lightGray);
			}).growX();
			t.row().table(touch -> {
				touch.add("Touchable").fontScale(keyScale).color(Color.lightGray).growX().padRight(padRight);
				touch.add(touchableLabel).row();
			}).growX();
			t.row().table(color -> {
				color.add("Color").fontScale(keyScale).color(Color.lightGray).growX().padRight(padRight);
				color.add(colorContainer).size(16).padRight(4f);
				color.add(colorLabel).row();
			}).growX();
			rotCell = new BindCell(t.row().table(rot -> {
				rot.add("Rotation").fontScale(keyScale).color(Color.lightGray).growX().padRight(padRight);
				rot.add(rotationLabel).row();
			}).growX());
			translationCell = new BindCell(t.row().table(tran -> {
				tran.add("Translation").fontScale(keyScale).color(Color.lightGray).growX().padRight(padRight);
				tran.add(translationLabel).row();
			}).growX());
			styleCell = new BindCell(t.row().table(tran -> {
				tran.add("Style").fontScale(keyScale).color(Color.lightGray).growX().padRight(padRight);
				tran.add(styleLabel).color(Color.orange).row();
			}).growX());

			cellCell = new BindCell(t.row().table(c -> {
				colspanCell = new BindCell(c.row().table(col -> {
					col.add("Colspan").fontScale(keyScale).color(Color.lightGray).growX().padRight(padRight);
					col.add(colspanLabel).row();
				}).growX());
				minSizeCell = new BindCell(c.row().table(col -> {
					col.add("MinSize").fontScale(keyScale).color(Color.lightGray).growX().padRight(padRight);
					col.add(minSizeLabel).row();
				}).growX());
				maxSizeCell = new BindCell(c.row().table(col -> {
					col.add("MaxSize").fontScale(keyScale).color(Color.lightGray).growX().padRight(padRight);
					col.add(maxSizeLabel).row();
				}).growX());
			}).growX());
		}
	}

	private class ReviewFocusTask extends FocusTask {
		{drawSlightly = true;}

		public ReviewFocusTask() {super(ReviewElement.maskColor, ReviewElement.focusColor);}

		/** 清除elemDraw */
		public void elemDraw() {}
		public void beforeDraw(Window drawer) {
			if (drawer == FOCUS_WINDOW && FOCUS != null) {
				drawFocus(FOCUS);
			}
		}
		public void drawFocus(Element elem, Vec2 vec2) {
			super.afterAll();
			super.drawFocus(elem, vec2);
			Gl.flush();

			MyDraw.intoDraw(() -> drawGeneric(elem, vec2));
			Gl.flush();

			if (!hoverInfoWindow) return;
			table.nameLabel.setText(ElementUtils.getElementName(elem));
			table.sizeLabel.setText(fixed(elem.getWidth()) + " × " + fixed(elem.getHeight()));
			table.touchableLabel.setText(toString(elem.touchable));
			table.color(elem.color);
			table.rotation(elem.rotation);
			table.translation(elem.translation);
			table.style(elem);
			{
				Cell cell = null;
				if (elem.parent instanceof Table parent) cell = parent.getCell(elem);
				table.colspan(cell);
				table.minSize(cell);
				table.maxSize(cell);
			}

			showHover(elem, vec2);
			Gl.flush();
		}
		private CharSequence toString(Touchable touchable) {
			return switch (touchable) {
				case enabled -> "Enabled";
				case disabled -> "Disabled";
				case childrenOnly -> "Children Only";
			};
		}
		private void drawGeneric(Element elem, Vec2 vec2) {
			posLine:
			{
				if ((posLineColor & 0x000000FF) == 0) break posLine;
				Lines.stroke(4);
				Draw.color(posLineColor);
				// x: 0 -> x
				if (vec2.x != 0) Lines.line(0, vec2.y, vec2.x, vec2.y);
				// y: 0 -> y
				if (vec2.y != 0) Lines.line(vec2.x, 0, vec2.x, vec2.y);
			}
			posText:
			{
				if ((posTextColor & 0x000000FF) == 0) break posText;
				// x: 0 -> x
				if (vec2.x != 0) MyDraw.drawText(fixed(vec2.x),
				 vec2.x / 2f, vec2.y, Tmp.c1.set(posTextColor));
				// y: 0 -> y
				if (vec2.y != 0) MyDraw.drawText(fixed(vec2.y),
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
				if (w != 0) MyDraw.drawText(fixed(w),
				 vec2.x + w / 2f,
				 (flipY ? Core.graphics.getHeight() - MyDraw.fontHeight() : MyDraw.fontHeight()),
				 color, Align.center);

				// height
				if (h != 0) MyDraw.drawText(fixed(h),
				 flipX ? Core.graphics.getWidth() : 0,
				 vec2.y + (h + MyDraw.fontHeight()) / 2f,
				 color, flipX ? Align.right : Align.left);
			}

			if (elem instanceof Table) {
				drawMargin(vec2, (Table) elem);
			}

			if (elem.parent instanceof Table parent) {
				drawMargin(parent.localToStageCoordinates(Tmp.v1.set(0, 0)), parent);

				drawPadding(elem, vec2, parent);
			}
		}

		// ---------------------
		private void showHover(Element elem, Vec2 vec2) {
			table.cellCell.toggle(
			 ((Table) table.cellCell.el).getChildren().size > 2/* 两个基础元素 */
			);
			// table.layout();
			table.invalidate();
			table.getPrefWidth();
			table.act(0);
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
				// if (y + table.getPrefHeight() > Core.graphics.getHeight()) y = 0;
				table.top();
				if (y - table.getPrefHeight() < 0) {
					table.bottom();
					y = 0;
				}
			}
			/* if () {
				table.bottom();
				if (!(y < vec2.y && vec2.y + table.getPrefHeight() > y
							&& y + table.getPrefHeight() < Core.graphics.getHeight())) {
					y = 0;
				}
			} */
			table.setPosition(x, y);
			table.draw();
		}
		final InfoDetails table = new InfoDetails();

		private boolean isSelecting() {
			return topGroup.elementCallback == callback;
		}
		public void endDraw() {
			if (isSelecting()) super.endDraw();
			drawLine();
			elem = topGroup.getSelected();
			if (elem != null) drawFocus(elem);
		}
	}
}
