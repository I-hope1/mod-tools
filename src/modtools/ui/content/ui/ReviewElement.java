package modtools.ui.content.ui;

import arc.Core;
import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.annotations.builder.DataColorFieldInit;
import modtools.events.ISettings;
import modtools.jsfunc.*;
import modtools.ui.*;
import modtools.ui.TopGroup.*;
import modtools.ui.components.*;
import modtools.ui.components.Window.IDisposable;
import modtools.ui.components.buttons.FoldedImageButton;
import modtools.ui.components.input.*;
import modtools.ui.components.limit.LimitTable;
import modtools.ui.components.linstener.FocusSearchListener;
import modtools.ui.components.review.CellDetailsWindow;
import modtools.ui.components.utils.*;
import modtools.ui.components.windows.ListDialog.ModifiedLabel;
import modtools.ui.content.Content;
import modtools.ui.content.ui.PairProv.SizeProv;
import modtools.ui.control.HopeInput;
import modtools.ui.effect.*;
import modtools.ui.gen.HopeIcons;
import modtools.ui.menu.*;
import modtools.utils.*;
import modtools.utils.JSFunc.JColor;
import modtools.utils.MySettings.Data;
import modtools.utils.ui.*;
import modtools.utils.ui.search.BindCell;

import java.util.regex.*;

import static arc.Core.scene;
import static modtools.ui.Contents.review_element;
import static modtools.ui.HopeStyles.defaultLabel;
import static modtools.ui.IntUI.*;
import static modtools.ui.content.ui.ReviewElement.Settings.hoverInfoWindow;
import static modtools.utils.Tools.Sr;
import static modtools.utils.ui.FormatHelper.*;
import static modtools.utils.world.TmpVars.mouseVec;

/** It should be `InspectElement`, but it's too late. */
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

	public ReviewElement() {
		super("reviewElement", HopeIcons.codeSmall);
	}

	public static final boolean    DEBUG       = false;
	public static final LabelStyle LABEL_STYLE = new LabelStyle(MyFonts.def, Color.sky);


	public static Element FOCUS;
	/**
	 * focus的来源元素
	 */
	public static Element FOCUS_FROM;
	public static Window  FOCUS_WINDOW;

	public static void addFocusSource(Element source, Prov<Window> windowProv,
	                                  Prov<Element> focusProv) {
		if (focusProv == null) throw new IllegalArgumentException("focusProv is null.");
		if (windowProv == null) throw new IllegalArgumentException("windowProv is null.");
		source.addListener(new HoverAndExitListener() {
			public void enter0(InputEvent event, float x, float y, int pointer, Element fromActor) {
				FOCUS_FROM = source;
				FOCUS = focusProv.get();
				FOCUS_WINDOW = windowProv.get();
			}
			public void exit0(InputEvent event, float x, float y, int pointer, Element toActor) {
				if (toActor != null && source.isAscendantOf(toActor) && toActor.getScene() != null
					/*  && source.getListeners().find(t -> this.getClass().isInstance(t)) == null */) return;
				CANCEL_TASK.run();
			}
		});
	}
	public static final Color FOCUS_COLOR = DEF_FOCUS_COLOR;
	public static final Color MASK_COLOR  = DEF_MASK_COLOR;

	public void loadSettings(Data SETTINGS) {
		Contents.settings_ui.add(localizedName(), icon, new Table() {{
			left().defaults().left();
			table(t -> {
				ISettings.buildAll("", t, Settings.class);
				settingColor(t);
			}).grow();
		}});
	}

	/** 代码生成{@link  ColorProcessor} */
	public void settingColor(Table t) { }


	ReviewFocusTask task;
	public void load() {
		task = new ReviewFocusTask();

		loadSettings();
		scene.root.getCaptureListeners().insert(0, new InputListener() {
			public boolean keyDown(InputEvent event, KeyCode keycode) {
				if (Core.input.ctrl() && Core.input.shift() && keycode == KeyCode.c) {
					build();
					HopeInput.justPressed.clear();
					event.stop();
				}
				if (!(Core.input.ctrl() && Core.input.alt() && keycode == KeyCode.d)) return true;
				if (Core.input.shift()) {
					if (topGroup.isSelecting()) {
						return false;
					}
					TSettings.debugBounds.set(true);
					topGroup.requestSelectElem(TopGroup.defaultDrawer, topGroup::setDrawPadElem);
				} else TSettings.debugBounds.toggle();
				return true;
			}
		});

		topGroup.focusOnElement(task);

		TopGroup.classBlackList.add(ReviewElementWindow.class);
	}
	public Button buildButton(boolean isSmallized) {
		Button btn = buildButton(isSmallized, () -> task.isSelecting());
		TopGroup.searchBlackList.add(btn);
		return btn;
	}

	public void drawPadding(Element elem, Vec2 vec2, Table table) {
		/* 如果a = 0就返回 */
		if (checkA(padColor)) return;
		Draw.color(padColor);
		Cell<?> cl = table.getCell(elem);
		if (cl == null) {
			return;
		}
		float padLeft = Reflect.get(Cell.class, cl, "padLeft"),
		 padTop = Reflect.get(Cell.class, cl, "padTop"),
		 padBottom = Reflect.get(Cell.class, cl, "padBottom"),
		 padRight = Reflect.get(Cell.class, cl, "padRight");

		drawMarginOrPad(vec2, elem, true, padLeft, padTop, padRight, padBottom);
	}
	boolean checkA(int color) {
		// 检查后两位是否为0
		return (color & 0xFF) == 0;
	}

	public void drawMargin(Vec2 vec2, Table table) {
		if (checkA(marginColor)) return;
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

		Vec2 vec2 = ElementUtils.getAbsPosCenter(FOCUS);
		Draw.color(ColorFul.color);
		Lines.stroke(3f);
		Lines.line(mouseVec.x, mouseVec.y, vec2.x, vec2.y);
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
		private static final String SEARCH_RESULT = "SRCH_RS";
		Table   pane = new LimitTable() { };
		Element element;
		Pattern pattern;

		MyWrapTable fixedFocus;

		public ReviewElementWindow() {
			super(review_element.localizedName(), 20, 160, true);
			getCell(cont).maxWidth(Core.graphics.getWidth());

			name = "ReviewElementWindow";

			pane.top().left().defaults().left().top();
			cont.table(t -> {
				Button[] bs = {null};
				bs[0] = t.button("@reviewElement.parent", Icon.upSmall, HopeStyles.flatBordert, () -> {
					 Runnable go = () -> {
						 var parentWindow = new ReviewElementWindow();
						 // Log.info(element.parent);
						 /* 左上角对齐 */
						 parentWindow.pattern = pattern;
						 parentWindow.shown(() -> Core.app.post(() -> {
							 parentWindow.pane.parent.setSize(pane.parent.getWidth(), pane.parent.getHeight());
							 parentWindow.setSize(width, height);
							 parentWindow.setPosition(x, getY(Align.topLeft), Align.topLeft);
						 }));
						 parentWindow.show(element.parent);
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
				 }).size(130, 35)
				 .padRight(3f).get();
				t.button(Icon.copySmall, HopeStyles.clearNonei, 28, () -> {
					 var window = new ReviewElementWindow();
					 window.pattern = pattern;
					 window.show(element);
					 window.shown(() -> window.setSize(width, height));
				 })
				 .size(35).padRight(3f);
				t.button(Icon.refreshSmall, HopeStyles.clearNonei, 28, () -> rebuild(element, pattern))
				 .size(35).padRight(3f);
				t.table(search -> {
					search.image(Icon.zoomSmall).size(35);
					search.field("", str -> rebuild(element, str))
					 .with(f -> f.setMessageText("@players.search"))
					 .with(f -> ReviewElementWindow.this.addCaptureListener(new FocusSearchListener(f)))
					 .growX();
				}).growX();
			}).growX().row();

			cont.add(new ScrollPane(pane, Styles.smallPane) {
				public String toString() {
					return DEBUG ? super.toString() : name;
				}
			}).grow().minHeight(120);

			// shown(pane::invalidateHierarchy);

			update(() -> {
				if (fixedFocus != null) {
					FOCUS = (Element) fixedFocus.userObject;
					FOCUS_WINDOW = this;
					FOCUS_FROM = fixedFocus;
				}
			});
			hidden(CANCEL_TASK);
		}

		public void rebuild(Element element, String text) {
			pane.clearChildren();

			if (element == null) return;
			Pattern pattern = PatternUtils.compileRegExpOrNull(text);
			rebuild(element, pattern);
		}

		public void rebuild(Element element, Pattern pattern) {
			pane.clearChildren();

			if (element == null) return;
			this.pattern = pattern;
			build(element, pane);

			// pane.row();
			// pane.image().color(Pal.accent).growX().padTop(8).padBottom(8).row();
			// highlightShowMultiRow(pane, pattern, element + "");
		}


		public void highlightShowMultiRow(Table table, String text) {
			addMultiRowWithPos(table, text, null);
		}
		/** 结构： Label，Image（下划线） */
		public void addMultiRowWithPos(Table table, String text, Prov<Vec2> pos) {
			wrapTable(table, pos,
			 pattern == null ?
				t -> t.add(new MyLabel(text, defaultLabel)).left().color(Pal.accent)
				: t -> {
				 for (var line : text.split("\\n")) {
					 highlightShow(t.row(), pattern, line);
				 }
			 });
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
						lastIndex++;
					}
					// Log.info("i: @, l: @", index, lastIndex);
					t.table(IntUI.whiteui.tint(Pal.logicWorld), t1 -> {
						t1.add(new MyLabel(curText, LABEL_STYLE)).padRight(1);
					}).name(SEARCH_RESULT);
				}
				if (text.length() - lastIndex > 0)
					t.add(text.substring(lastIndex), defaultLabel).color(Pal.accent);
			});
		}
		public void build(Element element, Table table) {
			if (element == null) throw new IllegalArgumentException("element is null");
			if (!DEBUG && element instanceof ReviewElementWindow) {
				table.add(STR."----\{name}-----", defaultLabel).row();
				return;
			}
			table.left().defaults().left().growX();
			IntVars.postToMain(() -> {
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
			rebuild(element, pattern);
			show();
		}

		public String toString() {
			if (DEBUG) return name;
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
	static void wrapTable(Table table, Prov<Vec2> pos, Cons<Table> cons) {
		table.table(t -> {
			t.left().defaults().left();
			cons.get(t);
			makePosLabel(t, pos);
		}).growX().left().row();
		table.image().color(Tmp.c1.set(JColor.c_underline)).growX().colspan(2).row();
	}

	static void makePosLabel(Table t, Prov<Vec2> pos) {
		if (pos != null) t.label(new PairProv(pos, ", "))
		 .style(defaultLabel).color(Color.lightGray)
		 .fontScale(0.7f).padLeft(4f).padRight(4f);
	}

	private static class MyWrapTable extends ChildrenFirstTable implements KeyValue {
		boolean stopEvent, needUpdate;
		ReviewElementWindow window;

		Element previousKeyboardFocus;
		public void requestKeyboard() {
			if (scene.hasField()) return;
			previousKeyboardFocus = scene.getKeyboardFocus();
			super.requestKeyboard();
		}
		public void unfocus() {
			if (scene.getKeyboardFocus() == this) scene.setKeyboardFocus(previousKeyboardFocus);
		}
		public MyWrapTable(ReviewElementWindow window, Element element) {
			this.window = window;
			userObject = element;

			hovered(this::requestKeyboard);
			exited(this::unfocus);
			keyDown(KeyCode.f, () -> window.fixedFocus = window.fixedFocus == this ? null : this);
			keyDown(KeyCode.i, () -> INFO_DIALOG.showInfo(element));
			keyDown(KeyCode.r, () -> IntUI.showMenuList(execChildren(element)));
			keyDown(KeyCode.del, () -> {
				Runnable go = () -> {
					remove();
					element.remove();
				};
				if (Core.input.shift()) {
					go.run();
				} else {
					IntUI.showConfirm("@confirm.remove", go);
				}
			});


			/* 用于下面的侦听器  */
			int eventChildIndex;
			/* 用于添加侦听器 */
			if (element instanceof Group group) {
				/* 占位符 */
				var button   = new FoldedImageButton(true);
				int size     = 32;
				var children = group.getChildren();
				add(button).size(size).disabled(_ -> children.isEmpty());
				eventChildIndex = 1;
				window.addMultiRowWithPos(this,
				 ElementUtils.getElementName(element),
				 () -> Tmp.v1.set(element.x, element.y));
				Element textElement = ((Table) this.children.get(this.children.size - 2)).getChildren().first();
				// Log.info(textElement);

				image().growY().left().update(
				 t -> t.color.set(FOCUS_FROM == this ? ColorFul.color : Color.darkGray)
				);
				keyDown(KeyCode.left, () -> button.fireCheck(false));
				keyDown(KeyCode.right, () -> button.fireCheck(true));
				defaults().growX();
				table(t -> {
					Table    table1  = new Table();
					Runnable rebuild = () -> watchChildren(window, group, table1, children);

					boolean b = children.size < 20
					            || group.parent.getChildren().size == 1
					            || window.element == group;
					if (b) rebuild.run();
					button.setContainer(t.add(table1).grow());
					boolean[] lastEmpty = {children.isEmpty()};
					t.update(() -> {
						if (children.isEmpty() != lastEmpty[0]) {
							lastEmpty[0] = children.isEmpty();
							HopeFx.changedFx(textElement);
						}
					});
					// button.setChecked(!children.isEmpty() && b);
					table1.update(() -> {
						if (needUpdate) {
							button.rebuild.run();
							needUpdate = false;
							return;
						}
						button.fireCheck(!children.isEmpty() && b, false);
						if (stopEvent) {
							stopEvent = false;
							return;
						}

						if (!group.needsLayout() || !parentValid(group, window)) return;

						ElementUtils.findParent(this, ancestor -> {
							if (ancestor instanceof MyWrapTable wrapTable) wrapTable.stopEvent = true;
							if (ancestor instanceof Window) return true;
							return false;
						});
						button.rebuild.run();
					});
					button.rebuild = () -> {
						if (parentValid(group, window) && (
						 needUpdate || group.needsLayout() ||
						 (!table1.hasChildren() && group.hasChildren())
						)) {
							rebuild.run();
							// if (group.needsLayout()) HopeFx.changedFx(group);
							HopeFx.changedFx(textElement);
						}
					};
				}).left();
			} else {
				defaults().growX();
				eventChildIndex = 0;
				window.addMultiRowWithPos(this, String.valueOf(element),
				 () -> Tmp.v1.set(element.x, element.y));
			}
			// JSFunc.addStoreButton(wrap, "element", () -> element);
			Element window_elem = this.children.get(eventChildIndex);
			if (element instanceof Image img) {
				keyDown(KeyCode.p, () -> IntUI.drawablePicker().show(img.getDrawable(), true, img::setDrawable));
				buildImagePreviewButton(element, (Table) window_elem, img::getDrawable, img::setDrawable);
			}
			window_elem.touchable = Touchable.enabled;
			Runnable copy = storeRun(() -> element);
			IntUI.addShowMenuListenerp(window_elem, getContextMenu(this, element, copy));
			IntUI.doubleClick(window_elem, null, copy);
			touchable = Touchable.enabled;

			update(() -> {
				if (!parentValid(element, window)) remove();
				background(FOCUS_FROM == this ? Styles.flatDown : Styles.none);
			});
			addFocusSource(this, () -> window, () -> element);
		}
		public void clear() {
			super.clear();
			userObject = null;
		}
		public boolean remove() {
			parentNeedUpdate();
			userObject = null;
			if (window.fixedFocus == this) CANCEL_TASK.run();
			return super.remove();
		}
		void parentNeedUpdate() {
			MyWrapTable table = ElementUtils.findParent(this, e -> e instanceof MyWrapTable);
			if (table != null) table.needUpdate = true;
		}
		static Prov<Seq<MenuItem>> getContextMenu(MyWrapTable self, Element element, Runnable copy) {
			return () -> Sr(Seq.with(
			 copyAsJSMenu(null, copy),
			 ConfirmList.with("clear", Icon.trashSmall, "@clear", "@confirm.remove", () -> {
				 self.remove();
				 element.remove();
			 }),
			 MenuItem.with("path.copy", Icon.copySmall, "@copy.path", () -> {
				 JSFunc.copyText(getPath(element));
			 }),
			 MenuItem.with("screenshot", Icon.fileImageSmall, "@reviewElement.screenshot", () -> {
				 ElementUtils.quietScreenshot(element);
			 }),
			 MenuItem.with("debug.bounds", Icon.adminSmall, "@settings.debugbounds", () -> REVIEW_ELEMENT.toggleDrawPadElem(element)),
			 MenuItem.with("window.new", Icon.copySmall, "New Window", () -> new ReviewElementWindow().show(element)),
			 MenuItem.with("details", Icon.infoSmall, "@details", () -> INFO_DIALOG.showInfo(element)),
			 FoldedList.withf("exec", Icon.boxSmall, "Exec", () -> execChildren(element)),
			 ValueLabel.newElementDetailsList(element)
			))
			 .ifRun(element instanceof Table, seq -> seq.add(
				MenuItem.with("allcells", Icon.wavesSmall, "All Cells", () -> viewAllCells(self, (Table) element))
			 ))
			 .ifRun(element == null || element.parent instanceof Table, seq -> seq.add(
				DisabledList.withd("this.cell", Icon.wavesSmall, "This Cell",
				 () -> element == null || !(element.parent instanceof Table && ((Table) element.parent).getCell(element) != null), () -> {
					 new CellDetailsWindow(((Table) element.parent).getCell(element)).show();
				 }))).get();
		}
		private static Seq<MenuItem> execChildren(Element element) {
			return Seq.with(
			 MenuItem.with("invalidate", Icon.boxSmall, "Invalidate", element::invalidate),
			 MenuItem.with("invalidateHierarchy", Icon.boxSmall, "InvalidateHierarchy", element::invalidateHierarchy),
			 MenuItem.with("layout", Icon.boxSmall, "Layout", element::layout),
			 MenuItem.with("pack", Icon.boxSmall, "Pack", element::pack),
			 MenuItem.with("validate", Icon.boxSmall, "Validate", element::validate),
			 MenuItem.with("keepInStage", Icon.boxSmall, "Keep in stage", element::keepInStage),
			 MenuItem.with("toFront", Icon.boxSmall, "To Front", element::toFront),
			 MenuItem.with("toBack", Icon.boxSmall, "To Back", element::toBack)
			);
		}
		private static CharSequence getPath(Element element) {
			if (element == null) return "null";
			Element       el = element;
			StringBuilder sb = new StringBuilder();
			while (el != null) {
				if (el.name != null) {
					return STR."Core.scene.find(\"\{el.name}\")\{sb}";
				} else if (el instanceof Group && ShowUIList.uiKeyMap.containsKey(el)) {
					return STR."Vars.ui.\{ShowUIList.uiKeyMap.get(el)}\{sb}";
				} else {
					sb.append(".children.get(").append(el.getZIndex()).append(')');
					el = el.parent;
				}
			}
			return element.getScene() != null ? STR."Core.scene.root\{sb}" : sb.delete(0, 0);
		}
	}

	private static Window viewAllCells(MyWrapTable self, Table element) {
		class AllCellsWindow extends Window implements IDisposable {
			public AllCellsWindow() { super("All Cells"); }
		}

		class CellItem extends Table implements CellView {
			public CellItem(Drawable background, Cons<Table> cons) {
				super(background, cons);
			}
		}
		Window d         = new AllCellsWindow();
		Table  container = new Table();
		d.cont.pane(container).grow();
		container.left().defaults().left();
		for (var cell : element.getCells()) {
			container.add(new CellItem(Tex.pane, t0 -> {
				 var l = new PlainValueLabel<>(Cell.class, () -> cell);
				 ReviewElement.addFocusSource(t0, () -> d, cell::get);
				 t0.add(l).grow();
			 })).grow()
			 .colspan(ElementUtils.getColspan(cell));
			if (cell.isEndRow()) {
				Underline.of(container.row(), 20);
			}
		}
		d.update(() -> d.display());
		return d;
	}


	static boolean parentValid(Element element, ReviewElement.ReviewElementWindow window) {
		return element.parent != null || element == window.element;
	}

	/** 监视children的变化  */
	private static void watchChildren(ReviewElementWindow window, Group group, Table container,
	                                  SnapshotSeq<Element> children) {
		if (!container.hasChildren()) {
			children.each(c -> window.build(c, container));
			return;
		}
		Cell<?>[] cells = new Cell<?>[children.size];
		container.getChildren().each(item -> {
			if (item instanceof MyWrapTable wrapTable) {
				Element data = (Element) wrapTable.userObject;
				if (data == null || data.parent != group) return;
				int index = data.getZIndex();
				if (index == -1) return;

				Cell cell = container.getCell(item);
				cells[index] = cell;
			}
		});
		for (int i = 0; i < children.size; i++) {
			if (cells[i] != null) continue;
			window.build(children.get(i), container);
			cells[i] = container.getCells().get(container.getCells().size - 1);
		}
		container.getCells().set(cells);
	}

	public static Table floatSetter(String name, Prov<CharSequence> def, Floatc floatc) {
		return new Table(t -> {
			if (name != null)
				t.add(name).color(Pal.accent).fontScale(0.7f).padRight(8f);
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


	public enum Settings implements ISettings {
		hoverInfoWindow/* , contextMenu(MenuItem[].class, MyWrapTable.getContextMenu(null, null, null)) */;

		Settings() { }
		Settings(Class<?> a, Prov<Seq<MenuItem>> prov) { }
	}

	public static class InfoDetails extends Table implements KeyValue {
		Label nameLabel = new VLabel(0.75f, Color.violet),
		 sizeLabel      = new VLabel(valueScale, Color.lightGray),
		 touchableLabel = new NoMarkupLabel(valueScale),

		// transformLabel    = new MyLabel(""),
		colorLabel        = new NoMarkupLabel(valueScale),
		 rotationLabel    = new VLabel(valueScale, Color.lightGray),
		 translationLabel = new VLabel(valueScale, Color.orange),
		 styleLabel       = new Label(""),
		 alignLabel       = new VLabel(valueScale, Color.sky),

		colspanLabel  = new VLabel(valueScale, Color.lightGray),
		 minSizeLabel = new Label(""), maxSizeLabel = new Label(""),
		 fillLabel    = new Label(""), expandLabel = new Label("");
		ColorContainer colorContainer = new ColorContainer(Color.white);

		BindCell rotCell, translationCell, styleCell, alignCell,
		 cellCell,
		 colspanCell, minSizeCell, maxSizeCell,
		 fillCell, expandCell;

		void color(Color color) {
			colorContainer.setColorValue(color);
			String string = color.toString().toUpperCase();
			colorLabel.setText(color.a == 1 ? string.substring(0, 6) : string);
		}
		void rotation(float rotation) {
			if (rotCell.toggle1(rotation % 360 != 0))
				rotationLabel.setText(fixed(rotation));
		}
		void translation(Vec2 translation) {
			if (translationCell.toggle1(!Mathf.zero(translation.x) || !Mathf.zero(translation.y)))
				translationLabel.setText(STR."\{fixed(translation.x)} × \{fixed(translation.y)}");
		}
		void style(Element element) {
			try {
				Style style = (Style) element.getClass().getMethod("getStyle", (Class<?>[]) null).invoke(element, (Object[]) null);
				if (styleCell.toggle1(style != null && ShowUIList.styleKeyMap.containsKey(style)))
					styleLabel.setText(StringHelper.fieldFormat(ShowUIList.styleKeyMap.get(style)));
			} catch (Throwable e) { styleCell.remove(); }
		}
		void align(Element element) {
			if (alignCell.toggle1(element instanceof Table))
				alignLabel.setText(StringHelper.align(((Table) element).getAlign()));
		}
		void colspan(Cell<?> cell) {
			if (cell == null) {
				colspanCell.remove();
				return;
			}
			int colspan = Reflect.get(Cell.class, cell, "colspan");
			if (colspanCell.toggle1(colspan != 1))
				colspanLabel.setText("" + colspan);
		}
		Vec2 minSizeVec = new Vec2(), maxSizeVec = new Vec2();
		SizeProv minSizeProv = new SizeProv(() -> minSizeVec.scl(1 / Scl.scl()));
		SizeProv maxSizeProv = new SizeProv(() -> maxSizeVec.scl(1 / Scl.scl()));
		void minSize(Cell<?> cell) {
			if (cell == null) {
				minSizeCell.remove();
				return;
			}
			float minWidth = Reflect.get(Cell.class, cell, "minWidth"),
			 minHeight = Reflect.get(Cell.class, cell, "minHeight");
			if (minSizeCell.toggle1(minWidth != unset || minHeight != unset)) {
				minSizeVec.set(minWidth, minHeight);
				minSizeLabel.setText(minSizeProv.get());
			}
		}
		void maxSize(Cell<?> cell) {
			if (cell == null) {
				maxSizeCell.remove();
				return;
			}
			float maxWidth = Reflect.get(Cell.class, cell, "maxWidth"),
			 maxHeight = Reflect.get(Cell.class, cell, "maxHeight");
			if (maxSizeCell.toggle1(maxWidth != unset || maxHeight != unset)) {
				maxSizeVec.set(maxWidth, maxHeight);
				maxSizeLabel.setText(maxSizeProv.get());
			}
		}
		void fill(Cell<?> cell) {
			if (cell == null) {
				fillCell.remove();
				return;
			}
			float fillX = Reflect.get(Cell.class, cell, "fillX"),
			 fillY = Reflect.get(Cell.class, cell, "fillY");
			if (fillCell.toggle1(fillX != 0 || fillY != 0))
				fillLabel.setText(STR."\{enabledMark((int) fillX)}x []| \{enabledMark((int) fillY)}y");
		}
		void expand(Cell<?> cell) {
			if (cell == null) {
				expandCell.remove();
				return;
			}
			int expandX = Reflect.get(Cell.class, cell, "expandX"),
			 expandY = Reflect.get(Cell.class, cell, "expandY");
			if (expandCell.toggle1(expandX != 0 || expandY != 0))
				expandLabel.setText(STR."\{enabledMark(expandX)}x []| \{enabledMark(expandY)}y");
		}
		static String enabledMark(int i) {
			return i == 1 ? "[accent]" : "[gray]";
		}

		InfoDetails() {
			margin(4, 4, 4, 4);
			table(Tex.pane, this::build);
			styleLabel.setFontScale(valueScale);
		}

		void setPosition(Element elem, Vec2 vec2) {
			bottom().left();

			// 初始在元素的左上角
			float x = vec2.x;
			float y = vec2.y + elem.getHeight();

			x = Mathf.clamp(x, 0, Core.graphics.getWidth() - getPrefWidth());
			if (y + getPrefHeight() > Core.graphics.getHeight()) {
				y = Math.min(vec2.y, Core.graphics.getHeight());

				top();
				if (y - getPrefHeight() < 0) {
					bottom();
					y = 0;
				}
			}
			setPosition(x, y);
		}
		private void build(Table t) {
			t.table(top -> {
				top.add(nameLabel).padLeft(-4f);
				top.add(sizeLabel).padLeft(10f)
				 .growX().right().labelAlign(Align.right);
			}).growX();
			t.row().table(tableCons("Touchable", touchableLabel)).growX();
			t.row().table(color -> {
				key(color, "Color");
				color.add(colorContainer).size(16).padRight(4f);
				color.add(colorLabel);
			}).growX();
			rotCell = makeCell(t, tableCons("Rotation", rotationLabel));
			translationCell = makeCell(t, tableCons("Translation", translationLabel));
			styleCell = makeCell(t, tableCons("Style", styleLabel));
			alignCell = makeCell(t, tableCons("Align", alignLabel));
			cellCell = makeCell(t, _ -> {
				colspanCell = makeCell(t, tableCons("Colspan", colspanLabel));
				minSizeCell = makeCell(t, tableCons("MinSize", minSizeLabel));
				maxSizeCell = makeCell(t, tableCons("MaxSize", maxSizeLabel));
				expandCell = makeCell(t, tableCons("Expand", expandLabel));
				fillCell = makeCell(t, tableCons("Fill", fillLabel));
			});
		}
	}

	class ReviewFocusTask extends FocusTask {
		{ drawSlightly = true; }

		public ReviewFocusTask() { super(ReviewElement.MASK_COLOR, ReviewElement.FOCUS_COLOR); }

		/** 清除elemDraw */
		public void elemDraw() { }
		public void beforeDraw(Window drawer) {
			if (drawer == FOCUS_WINDOW && FOCUS != null) {
				if (FOCUS_FROM instanceof CellView cw) {
					cw.drawFocus(FOCUS);
					Vec2 pos = ElementUtils.getAbsolutePos(FOCUS);
					// super.drawFocus(FOCUS, pos);
					MyDraw.intoDraw(() -> drawGeneric(FOCUS, pos));
				} else {
					drawFocus(FOCUS);
				}
			}
		}
		public void drawFocus(Element elem, Vec2 vec2) {
			super.afterAll();
			super.drawFocus(elem, vec2);

			MyDraw.intoDraw(() -> drawGeneric(elem, vec2));

			if (!hoverInfoWindow.enabled()) return;

			table.nameLabel.setText(ElementUtils.getElementName(elem));
			table.sizeLabel.setText(posText(elem));
			table.touchableLabel.setText(StringHelper.touchable(elem.touchable));
			table.touchableLabel.setColor(touchableToColor(elem.touchable));
			table.color(elem.color);
			table.rotation(elem.rotation);
			table.translation(elem.translation);
			table.style(elem);
			table.align(elem);
			{
				Cell cell = null;
				if (elem.parent instanceof Table parent) cell = parent.getCell(elem);
				table.colspan(cell);
				table.minSize(cell);
				table.maxSize(cell);
				table.fill(cell);
				table.expand(cell);
			}
			showInfoTable(elem, vec2);
		}
		private static String posText(Element elem) {
			return STR."\{fixed(elem.getWidth())} × \{fixed(elem.getHeight())}";
		}
		private void drawGeneric(Element elem, Vec2 vec2) {
			posText:
			{
				if (checkA(posTextColor)) break posText;
				/* // 相对坐标
				// x: 0 -> x
				if (elem.x != 0) MyDraw.drawText("x:" + fixed(vec2.x),
				 vec2.x / 3f, vec2.y, Tmp.c1.set(posTextColor));
				// y: 0 -> y
				if (elem.y != 0) MyDraw.drawText("y:" + fixed(vec2.y),
				 vec2.x, vec2.y / 3f, Tmp.c1.set(posTextColor)); */

				// 绝对坐标
				// x: 0 -> x
				if (vec2.x != 0) MyDraw.drawText(fixed(vec2.x),
				 vec2.x / 2f, vec2.y, Tmp.c1.set(posTextColor));
				// y: 0 -> y
				if (vec2.y != 0) MyDraw.drawText(fixed(vec2.y),
				 vec2.x, vec2.y / 2f, Tmp.c1.set(posTextColor));
			}
			posLine:
			{
				if (checkA(posLineColor)) break posLine;
				Lines.stroke(4);
				Draw.color(posLineColor);
				// x: 0 -> x
				if (vec2.x != 0) Lines.line(0, vec2.y, vec2.x, vec2.y);
				// y: 0 -> y
				if (vec2.y != 0) Lines.line(vec2.x, 0, vec2.x, vec2.y);
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
		private void showInfoTable(Element elem, Vec2 vec2) {
			table.cellCell.toggle(
			 ((Table) table.cellCell.el).getChildren().size > 2/* 两个基础元素 */
			);
			table.invalidate();
			table.getPrefWidth();
			table.act(0);
			table.setPosition(elem, vec2);
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
	static Color DISABLED_COLOR = new Color(0xFF0000_FF);
	static Color touchableToColor(Touchable touchable) {
		return switch (touchable) {
			case enabled -> Color.green;
			case disabled -> DISABLED_COLOR;
			case childrenOnly -> Pal.accent;
		};
	}

	/** 如果source元素有CellView接口，drawFocus按照下面来  */
	public interface CellView {
		default void drawFocus(Element focus) {
			if (focus == null || !(focus.parent instanceof Table table)) return;
			drawFocus(table.getCell(focus), focus);
		}
		default void drawFocus(Cell<?> cl, Element focus) {
			int   column =  CellTools.column(cl);
			int   row    =  CellTools.row(cl);
			Table table  = cl.getTable();
			float spanW  = table.getColumnWidth(column), spanH = table.getRowHeight(row);

			// 父元素的坐标
			Vec2 offset = ElementUtils.getAbsolutePos(table);

			float thick = 2f;
			Lines.stroke(thick);

			//逆运算下面过程
			/* align = c.align;
            if((align & Align.left) != 0)
                c.elementX = currentX;
            else if((align & Align.right) != 0)
                c.elementX = currentX + spannedCellWidth - c.elementWidth;
            else
                c.elementX = currentX + (spannedCellWidth - c.elementWidth) / 2;

            if((align & Align.top) != 0)
                c.elementY = currentY + c.computedPadTop;
            else if((align & Align.bottom) != 0)
                c.elementY = currentY + rowHeight[c.row] - c.elementHeight - c.computedPadBottom;
            else
                c.elementY = currentY + (rowHeight[c.row] - c.elementHeight + c.computedPadTop - c.computedPadBottom) / 2;
                 */
			int align = CellTools.align(cl);
			float padTop = CellTools.padTop(cl),
			 padBottom = CellTools.padBottom(cl) ,
			 padLeft =  CellTools.padLeft(cl),
			 padRight =  CellTools.padRight(cl);
			// Log.info(padTop);

			// 左下角为 (0, 0)
			float spanX = (align & Align.left) != 0 ? -padLeft :
			 (align & Align.right) != 0 ? padRight - spanW :
				// center
				(focus.getWidth() - spanW - padLeft + padRight) / 2f;
			float spanY = (align & Align.top) != 0 ? padTop - spanH + focus.getHeight() :
			 (align & Align.bottom) != 0 ? -padBottom :
				(focus.getHeight() - spanH - padBottom + padTop) / 2f;


			Draw.color(Pal.remove);
			Drawf.dashRectBasic(focus.x + spanX + offset.x, focus.y + spanY + offset.y, spanW + thick, spanH + thick);

			thick = 1f;
			Draw.color(Pal.heal);
			Drawf.dashRectBasic(focus.x + offset.x, focus.y + offset.y, focus.getWidth() + thick, focus.getHeight() + thick);
		}
	}
}