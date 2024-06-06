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
import arc.scene.style.Style;
import arc.scene.ui.*;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.annotations.OptimizeReflect;
import modtools.annotations.builder.DataColorFieldInit;
import modtools.events.ISettings;
import modtools.jsfunc.*;
import modtools.ui.*;
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
import modtools.ui.effect.*;
import modtools.ui.gen.HopeIcons;
import modtools.ui.menu.*;
import modtools.utils.*;
import modtools.utils.JSFunc.JColor;
import modtools.utils.MySettings.Data;
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

	public static final boolean    hideSelf  = true;
	public static final LabelStyle skyMyFont = new LabelStyle(MyFonts.def, Color.sky);


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
		source.addListener(new InputListener() {
			public void enter(InputEvent event, float x, float y, int pointer, Element fromActor) {
				FOCUS_FROM = source;
				FOCUS = focusProv.get();
				FOCUS_WINDOW = windowProv.get();
			}
			public void exit(InputEvent event, float x, float y, int pointer, Element toActor) {
				if (toActor != null && source.isAscendantOf(toActor)) return;
				CANCEL_TASK.run();
			}
		});
	}
	public static final Color focusColor = DEF_FOCUS_COLOR;
	public static final Color maskColor  = DEF_MASK_COLOR;

	public void loadSettings(Data SETTINGS) {
		Contents.settings_ui.add(localizedName(), icon, new Table() {{
			left().defaults().left();
			table(t -> {
				ISettings.buildAll("", t, Settings.class);
				settingColor(t);
			}).grow();
		}});
	}

	/** 代码生成{@link ColorProcessor} */
	public void settingColor(Table t) { }


	ReviewFocusTask task;
	public void load() {
		loadSettings();
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

		task = new ReviewFocusTask();
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
		@OptimizeReflect
		float padLeft = Reflect.get(Cell.class, cl, "padLeft"),
		 padTop = Reflect.get(Cell.class, cl, "padTop"),
		 padBottom = Reflect.get(Cell.class, cl, "padBottom"),
		 padRight = Reflect.get(Cell.class, cl, "padRight");

		drawMarginOrPad(vec2, elem, true, padLeft, padTop, padRight, padBottom);
	}
	boolean checkA(int color) {
		return (color & 0x000000FF) == 0;
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

			cont.add(new ScrollPane(pane, Styles.smallPane) {
				public String toString() {
					if (hideSelf) return name;
					return super.toString();
				}
			}).grow().minHeight(120);

			// shown(pane::invalidateHierarchy);

			/* update(() -> {
				if (focusWindow instanceof ReviewElementWindow && !CANCEL_TASK.isScheduled()) {
					Timer.schedule(CANCEL_TASK, Time.delta / 60f);
				}
			}); */
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
				t -> t.add(new MyLabel(text, defaultLabel)).growX().left().color(Pal.accent)
				: t -> {
				 for (var line : text.split("\\n")) {
					 highlightShow(t, pattern, line);
					 t.row();
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
						t1.add(new MyLabel(curText, skyMyFont)).padRight(1);
					}).name(SEARCH_RESULT);
				}
				if (text.length() - lastIndex > 0)
					t.add(text.substring(lastIndex), defaultLabel).color(Pal.accent);
			});
		}
		public void build(Element element, Table table) {
			if (element == null) throw new IllegalArgumentException("element is null");
			if (hideSelf && element instanceof ReviewElementWindow) {
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
	private static void wrapTable(Table table, Prov<Vec2> pos, Cons<Table> cons) {
		table.table(t -> {
			t.left().defaults().left();
			makePosLabel(t, pos);
			cons.get(t);
		}).left().row();
		table.image().color(Tmp.c1.set(JColor.c_underline)).growX().colspan(2).row();
	}

	static void makePosLabel(Table t, Prov<Vec2> pos) {
		if (pos != null) t.label(new PositionProv(pos))
		 .style(defaultLabel).color(Color.lightGray)
		 .fontScale(0.7f).padLeft(4f).padRight(4f);
	}

	private static class MyWrapTable extends ChildrenFirstTable {
		boolean stopEvent, needUpdate;
		public MyWrapTable(ReviewElementWindow window, Element element) {
			/* 用于下面的侦听器  */
			int eventChildIndex;
			userObject = element;
			hovered(this::requestKeyboard);
			exited(() -> scene.setKeyboardFocus(null));
			keyDown(KeyCode.i, () -> INFO_DIALOG.showInfo(element));
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
				Element textElement = ((Table) this.children.get(this.children.size - 2)).getChildren().peek();

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

						if (!group.needsLayout() || !parentValid(group)) return;

						ElementUtils.findParent(this, ancestor -> {
							if (ancestor instanceof MyWrapTable wrapTable) wrapTable.stopEvent = true;
							if (ancestor instanceof Window) return true;
							return false;
						});
						button.rebuild.run();
					});
					button.rebuild = () -> {
						if (parentValid(group) && (
						 needUpdate || group.needsLayout() ||
						 (!table1.hasChildren() && group.hasChildren())
						)) {
							rebuild.run();
							// if (group.needsLayout()) HopeFx.changedFx(group);
							HopeFx.changedFx(textElement);
						}
					};
				}).left();
			} else if (element instanceof Image img) {
				eventChildIndex = 0;
				keyDown(KeyCode.p, () -> IntUI.drawablePicker().show(img.getDrawable(), true, img::setDrawable));
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
				eventChildIndex = 0;
				window.addMultiRowWithPos(this, String.valueOf(element),
				 () -> Tmp.v1.set(element.x, element.y));
			}
			// JSFunc.addStoreButton(wrap, "element", () -> element);
			Element  window_elem = getChildren().get(eventChildIndex);
			Runnable copy        = storeRun(() -> element);
			IntUI.addShowMenuListenerp(window_elem, getContextMenu(this, element, copy));
			IntUI.doubleClick(window_elem, null, copy);
			touchable = Touchable.enabled;

			update(() -> {
				if (!parentValid(element)) remove();
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
			return super.remove();
		}
		private void parentNeedUpdate() {
			MyWrapTable table = ElementUtils.findParent(this, e -> e instanceof MyWrapTable);
			if (table != null) table.needUpdate = true;
		}
		private static Prov<Seq<MenuItem>> getContextMenu(MyWrapTable self, Element element, Runnable copy) {
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
			 FoldedList.withf("exec", Icon.boxSmall, "Exec", () -> Seq.with(
				MenuItem.with("invalidate", Icon.boxSmall, "Invalidate", element::invalidate),
				MenuItem.with("invalidateHierarchy", Icon.boxSmall, "InvalidateHierarchy", element::invalidateHierarchy),
				MenuItem.with("layout", Icon.boxSmall, "Layout", element::layout),
				MenuItem.with("pack", Icon.boxSmall, "Pack", element::pack),
				MenuItem.with("validate", Icon.boxSmall, "Validate", element::validate),
				MenuItem.with("keepInStage", Icon.boxSmall, "Keep in stage", element::keepInStage),
				MenuItem.with("toFront", Icon.boxSmall, "To Front", element::toFront),
				MenuItem.with("toBack", Icon.boxSmall, "To Back", element::toBack)
			 )),
			 ValueLabel.newElementDetailsList(element)
			))
			 .ifRun(element instanceof Table, seq -> seq.add(
				MenuItem.with("allcells", Icon.waves, "Cells", () -> {
					INFO_DIALOG.dialog(d -> {
						Window window1 = ElementUtils.getWindow(self);
						d.left().defaults().left();
						for (var cell : ((Table) element).getCells()) {
							d.table(Tex.pane, t0 -> {
								 var l = new PlainValueLabel<>(Cell.class, () -> cell);
								 ReviewElement.addFocusSource(l, () -> window1, cell::get);
								 t0.add(l).grow();
							 }).grow()
							 .colspan(ElementUtils.getColspan(cell));
							if (cell.isEndRow()) {
								Underline.of(d.row(), 20);
								d.row();
							}
						}
					});
				})))
			 .ifRun(element == null || element.parent instanceof Table, seq -> seq.add(
				DisabledList.withd("this.cell", Icon.waves, "This Cell",
				 () -> element == null || !(element.parent instanceof Table && ((Table) element.parent).getCell(element) != null), () -> {
					 new CellDetailsWindow(((Table) element.parent).getCell(element)).show();
				 }))).get();
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
	private static boolean parentValid(Element element) {
		return element.parent != null || element == scene.root;
	}
	private static void watchChildren(ReviewElementWindow window, Group group, Table container,
	                                  SnapshotSeq<Element> children) {
		if (!container.hasChildren()) {
			children.each(c -> window.build(c, container));
			return;
		}
		// if (true) return;
		Cell<?>[] cells = new Cell<?>[children.size];
		// Seq<Element> toRemoved = new Seq<>();
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
		// Seq<Runnable> runs = new Seq<>();
		for (int i = 0; i < cells.length; i++) {
			if (cells[i] != null) continue;
			window.build(children.get(i), container);
			cells[i] = container.getCells().get(container.getCells().size - 1);
		}
		// runs.each(Runnable::run);
		container.getCells().set(cells);
	}

	public static Table floatSetter(String name, Prov<CharSequence> def, Floatc floatc) {
		return new Table(t -> {
			if (name != null)
				t.add(name).color(Pal.accent).fontScale(0.7f).labelAlign(Align.topLeft).growY().padRight(8f);
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


	public enum Settings implements ISettings {
		hoverInfoWindow/* , contextMenu(MenuItem[].class, MyWrapTable.getContextMenu(null, null, null)) */;

		Settings() { }
		Settings(Class<?> a, Prov<Seq<MenuItem>> prov) { }
	}

	@OptimizeReflect
	static class InfoDetails extends Table {
		public static final float keyScale   = 0.7f;
		public static final float valueScale = 0.6f;
		Label nameLabel = new NoMarkupLabel(0.75f),
		 sizeLabel      = new NoMarkupLabel(valueScale),
		 touchableLabel = new NoMarkupLabel(valueScale),

		// transformLabel    = new MyLabel(""),
		colorLabel        = new NoMarkupLabel(valueScale),
		 rotationLabel    = new NoMarkupLabel(valueScale),
		 translationLabel = new NoMarkupLabel(valueScale),
		 styleLabel       = new NoMarkupLabel(valueScale),
		 alignLabel       = new NoMarkupLabel(valueScale),

		colspanLabel  = new NoMarkupLabel(valueScale),
		 minSizeLabel = new Label(""), maxSizeLabel = new Label(""),
		 fillLabel    = new Label(""), expandLabel = new Label("");
		ColorContainer colorContainer = new ColorContainer(Color.white);

		BindCell rotCell, translationCell, styleCell, alignCell,
		 cellCell,
		 colspanCell, minSizeCell, maxSizeCell,
		 fillCell, expandCell;


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
				translationLabel.setText(STR."\{fixed(translation.x)} × \{fixed(translation.y)}");
		}
		void style(Element element) {
			try {
				Style style = (Style) element.getClass().getMethod("getStyle", (Class<?>[]) null).invoke(element, (Object[]) null);
				if (styleCell.toggle1(style != null && ShowUIList.styleKeyMap.containsKey(style)))
					styleLabel.setText(ShowUIList.styleKeyMap.get(style));
			} catch (Exception e) {
				styleCell.remove();
			}
		}
		void align(Element element) {
			if (alignCell.toggle1(element instanceof Table))
				alignLabel.setText(Strings.capitalize(Align.toString(((Table) element).getAlign()).replace(',', '-')));
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
		void fill(Cell<?> cell) {
			if (cell == null) {
				fillCell.remove();
				return;
			}
			@OptimizeReflect
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
			@OptimizeReflect
			int expandX = Reflect.get(Cell.class, cell, "expandX"),
			 expandY = Reflect.get(Cell.class, cell, "expandY");
			if (expandCell.toggle1(expandX != 0 || expandY != 0))
				expandLabel.setText(STR."\{enabledMark(expandX)}x []| \{enabledMark(expandY)}y");
		}
		private static String enabledMark(int i) {
			return i == 1 ? "[accent]" : "[gray]";
		}

		private static String sizeText(float w, float h) {
			return STR."\{
			 fixedUnlessUnset(w / Scl.scl())
			 }[accent]×[]\{
			 fixedUnlessUnset(h / Scl.scl())
			 }";
		}

		InfoDetails() {
			margin(4, 4, 4, 4);
			table(Tex.pane, this::build);
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
		public static final float padRight = 8f;
		private void build(Table t) {
			t.table(top -> {
				top.add(nameLabel).color(Color.violet).padLeft(-4f);
				top.add(sizeLabel).padLeft(10f)
				 .growX().right().labelAlign(Align.right).color(Color.lightGray);
			}).growX();
			t.row().table(touch -> {
				key(touch, "Touchable");
				touch.add(touchableLabel).row();
			}).growX();
			t.row().table(color -> {
				key(color, "Color");
				color.add(colorContainer).size(16).padRight(4f);
				color.add(colorLabel).row();
			}).growX();
			rotCell = makeBindCell(t, rot -> {
				key(rot, "Rotation");
				rot.add(rotationLabel).row();
			});
			translationCell = makeBindCell(t, tran -> {
				key(tran, "Translation");
				tran.add(translationLabel).row();
			});
			styleCell = makeBindCell(t, tran -> {
				key(tran, "Style");
				tran.add(styleLabel).color(Color.orange).row();
			});
			alignCell = makeBindCell(t, tran -> {
				key(tran, "Align");
				tran.add(alignLabel).color(Color.cyan).row();
			});

			cellCell = makeBindCell(t, c -> {
				colspanCell = makeBindCell(t, col -> {
					key(col, "Colspan");
					col.add(colspanLabel).fontScale(valueScale).row();
				});
				minSizeCell = makeBindCell(t, col -> {
					key(col, "MinSize");
					col.add(minSizeLabel).fontScale(valueScale).row();
				});
				maxSizeCell = makeBindCell(t, col -> {
					key(col, "MaxSize");
					col.add(maxSizeLabel).fontScale(valueScale).row();
				});
				expandCell = makeBindCell(t, col -> {
					key(col, "Expand");
					col.add(expandLabel).fontScale(valueScale).row();
				});
				fillCell = makeBindCell(t, col -> {
					key(col, "Fill");
					col.add(fillLabel).fontScale(valueScale).row();
				});
			});
		}
		private static void key(Table col, String Fill) {
			col.add(Fill).fontScale(keyScale).color(Color.lightGray).growX().padRight(padRight);
		}
		private BindCell makeBindCell(Table t, Cons<Table> cons) {
			return new BindCell(t.row().table(cons).growX());
		}
	}

	class ReviewFocusTask extends FocusTask {
		{ drawSlightly = true; }

		public ReviewFocusTask() { super(ReviewElement.maskColor, ReviewElement.focusColor); }

		/** 清除elemDraw */
		public void elemDraw() { }
		public void beforeDraw(Window drawer) {
			if (drawer == FOCUS_WINDOW && FOCUS != null) {
				drawFocus(FOCUS);
			}
		}
		public void drawFocus(Element elem, Vec2 vec2) {
			super.afterAll();
			super.drawFocus(elem, vec2);

			MyDraw.intoDraw(() -> drawGeneric(elem, vec2));

			if (!hoverInfoWindow.enabled()) return;
			table.nameLabel.setText(ElementUtils.getElementName(elem));
			table.sizeLabel.setText(posText(elem));
			table.touchableLabel.setText(touchableToString(elem.touchable));
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

			showHover(elem, vec2);
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
		private void showHover(Element elem, Vec2 vec2) {
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

	static Color disabledColor = new Color(0xFF0000_FF);
	private Color touchableToColor(Touchable touchable) {
		return switch (touchable) {
			case enabled -> Color.green;
			case disabled -> disabledColor;
			case childrenOnly -> Pal.accent;
		};
	}


	private static CharSequence touchableToString(Touchable touchable) {
		return switch (touchable) {
			case enabled -> "Enabled";
			case disabled -> "Disabled";
			case childrenOnly -> "Children Only";
		};
	}
}
