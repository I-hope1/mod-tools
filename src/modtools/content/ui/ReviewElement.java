package modtools.content.ui;

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
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.Styles;
import modtools.Constants.TABLE;
import modtools.IntVars;
import modtools.annotations.builder.DataColorFieldInit;
import modtools.content.Content;
import modtools.misc.PairProv;
import modtools.misc.PairProv.SizeProv;
import modtools.events.ISettings;
import modtools.jsfunc.*;
import modtools.jsfunc.reflect.UNSAFE;
import modtools.struct.TaskSet;
import modtools.ui.*;
import modtools.ui.TopGroup.*;
import modtools.ui.comp.*;
import modtools.ui.comp.Window.IDisposable;
import modtools.ui.comp.buttons.FoldedImageButton;
import modtools.ui.comp.input.*;
import modtools.ui.comp.input.area.AutoTextField;
import modtools.ui.comp.limit.LimitTable;
import modtools.ui.comp.linstener.FocusSearchListener;
import modtools.ui.comp.review.CellDetailsWindow;
import modtools.ui.comp.utils.*;
import modtools.ui.control.HKeyCode;
import modtools.ui.effect.*;
import modtools.ui.gen.HopeIcons;
import modtools.ui.menu.*;
import modtools.utils.*;
import modtools.utils.EventHelper.DoubleClick;
import modtools.utils.JSFunc.JColor;
import modtools.utils.MySettings.Data;
import modtools.utils.ui.*;
import modtools.utils.search.BindCell;
import modtools.utils.ui.LerpFun.DrawExecutor;

import java.util.regex.Pattern;

import static arc.Core.scene;
import static modtools.IntVars.mouseVec;
import static modtools.content.ui.ReviewElement.Settings.*;
import static modtools.ui.Contents.review_element;
import static modtools.ui.HopeStyles.defaultLabel;
import static modtools.ui.IntUI.*;
import static modtools.utils.ui.CellTools.unset;
import static modtools.utils.ui.FormatHelper.fixed;

/**
 * Ctrl+Shift+C审查元素
 * @author I-hope1 */
public class ReviewElement extends Content {
	@DataColorFieldInit(data = "", needSetting = true)
	public static int
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

	public static final boolean DEBUG = true;


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

	@SuppressWarnings("StringTemplateMigration")
	public static String getElementName(Element element) {
		return element == scene.root ? "ROOT"
		 : STR."""
		 \{anonymousInsteadSuper.enabled() ? element.getClass().getSimpleName() : ReflectTools.getSimpleNameNotAnonymous(element.getClass())}\
		 \{element instanceof TextButton tb && tb.getText().length() > 0 ? ": " + tb.getText() : ""}\
		 \{element.name != null ? " ★" + element.name + "★" : ""}\
		 """;
	}

	public void loadSettings(Data SETTINGS) {
		Contents.settings_ui.add(localizedName(), icon, new Table() {{
			left().defaults().left();
			table(t -> {
				ISettings.buildAll("", t, Settings.class);
				settingColor(t.table().growX().get());
			}).grow();
		}});
	}

	/** 代码生成{@code ColorProcessor} */
	public void settingColor(Table t) { }


	public HKeyCode inspectKeycode     =
	 keyCodeData().keyCode("inspect", () -> new HKeyCode(KeyCode.c).ctrl().shift())
		.applyToScene(true, this::build0);
	public HKeyCode debugBoundsKeyCode =
	 keyCodeData().keyCode("debugBounds", () -> new HKeyCode(KeyCode.d).ctrl().alt())
		.applyToScene(true, TSettings.debugBounds::toggle);

	public HKeyCode selectDebugBoundsKeyCode =
	 keyCodeData().keyCode("selectDebugBounds", () -> new HKeyCode(KeyCode.d).ctrl().alt().shift())
		.applyToScene(true, () -> {
			if (topGroup.isSelecting()) return;

			TSettings.debugBounds.set(true);
			topGroup.requestSelectElem(TopGroup.defaultDrawer, TopGroup::setDrawPadElem);
		});

	ReviewFocusTask task;
	public void load() {
		task = new ReviewFocusTask();

		topGroup.focusOnElement(task);
		if (!DEBUG) TopGroup.classBlackList.add(ReviewElementWindow.class);
		loadSettings();
	}
	public Button buildButton(boolean isSmallized) {
		Button btn = buildButton(isSmallized, () -> task.isSelecting());
		btn.addListener(new ITooltip(() -> tipKey("shortcuts", inspectKeycode.toString())));
		TopGroup.searchBlackList.add(btn);
		return btn;
	}

	public static void drawPadding(Element elem, Vec2 vec2, Table table) {
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
	static boolean checkA(int color) {
		// 检查后两位是否为0
		return (color & 0xFF) == 0;
	}

	public static void drawMargin(Vec2 vec2, Table table) {
		if (checkA(marginColor)) return;
		Draw.color(marginColor);

		drawMarginOrPad(vec2, table, false,
		 table.getMarginLeft(), table.getMarginTop(),
		 table.getMarginRight(), table.getMarginBottom());
	}
	/** @param pad true respect 外边距 */
	private static void drawMarginOrPad(
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

	public static class ReviewElementWindow extends Window implements IDisposable, DrawExecutor {
		private static final String SEARCH_RESULT = "SRCH_RS";
		/** 用于parent父元素时，不用重新遍历 */
		ElementElem wrapCache;

		Table   pane = new LimitTable();
		Element element;
		Pattern pattern;

		ElementElem fixedFocus;

		final TaskSet drawTaskSet = new TaskSet();
		public void draw() {
			super.draw();
			drawTaskSet.exec();
		}
		public TaskSet drawTaskSet() {
			return drawTaskSet;
		}

		public ReviewElementWindow() {
			super(review_element.localizedName(), 20, 160, true);
			getCell(cont).maxWidth(Core.graphics.getWidth());

			name = "ReviewElementWindow";

			pane.top().left().defaults().left().top();
			cont.table(t -> {
				Button[] bs = {null};
				bs[0] = t.button("@reviewElement.parent", Icon.upSmall, HopeStyles.flatBordert, () -> {
					 Runnable go = () -> {
						 Element first = pane.getChildren().first();
						 if (first instanceof ElementElem el) wrapCache = el;
						 if (first instanceof MyWrapTable wt) wt.removeInternal();
						 rebuild(element = element.parent);
						 wrapCache = null;
					 };
					 if (element.parent == scene.root) {
						 Vec2 vec2 = ElementUtils.getAbsolutePos(bs[0]);
						 showConfirm("@reviewElement.confirm.root", go).setPosition(vec2);
					 } else go.run();
				 })
				 .disabled(_ -> element == null || element.parent == null)
				 .with(b -> b.getLabel().setFontScale(0.9f))
				 .size(130, 35)
				 .padRight(3f).get();
				t.button(Icon.copySmall, HopeStyles.clearNonei, 28, () -> {
					 var window = new ReviewElementWindow();
					 window.pattern = pattern;
					 window.show(element);
					 window.shown(() -> window.setSize(width, height));
				 })
				 .size(35).padRight(3f);
				t.button(Icon.refreshSmall, HopeStyles.clearNonei, 28, () -> rebuild(element))
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

			MenuBuilder.addShowMenuListenerp(pane, ElementElem.class, target -> MyWrapTable.getContextMenu(target, target.getElement()));
			ObjectMap<KeyCode, Cons<ElementElem>> keyMap = new ObjectMap<>();
			keyMap.put(KeyCode.f, e -> fixedFocus = fixedFocus == e ? null : e);
			keyMap.put(KeyCode.i, e -> INFO_DIALOG.showInfo(e.getElement()));
			keyMap.put(KeyCode.r, e -> MenuBuilder.showMenuList(MyWrapTable.execChildren(e.getElement())));
			keyMap.put(KeyCode.del, e -> IntUI.shiftIgnoreConfirm(() -> {
				e.getElement().remove();
				e.remove();
			}));
			keyMap.put(KeyCode.p, e -> {
				if (e.getElement() instanceof Image img) {
					IntUI.drawablePicker().show(img.getDrawable(), true, img::setDrawable);
				}
			});
			pane.requestKeyboard();
			pane.addListener(new HoverAndExitListener() {
				public void enter0(InputEvent event, float x, float y, int pointer, Element fromActor) {
					MyWrapTable target = getTarget(event, MyWrapTable.class);
					if (target == null) return;
					target.requestKeyboard();

					if (fixedFocus != null) return;
					FOCUS_FROM = target;
					FOCUS = target.getElement();
					FOCUS_WINDOW = ReviewElementWindow.this;
				}
				public void exit0(InputEvent event, float x, float y, int pointer, Element toActor) {
					MyWrapTable target = getTarget(event, MyWrapTable.class);
					if (target == null) return;
					target.unfocus();

					if (fixedFocus != null) return;
					CANCEL_TASK.run();
				}
				@Override
				public boolean keyDown(InputEvent event, KeyCode keycode) {
					ElementElem target = getTarget(event, ElementElem.class);
					if (target == null) return false;
					if (keyMap.containsKey(keycode)) {
						keyMap.get(keycode).get(target);
						event.stop();
					}
					return true;
				}
				private <T> T getTarget(InputEvent event, Class<T> clazz) {
					return ElementUtils.findParent(event.targetActor, clazz);
				}
			});
			pane.addListener(new DoubleClick(null, null) {
				public void d_clicked(InputEvent event, float x, float y) {
					ElementElem target = ElementUtils.findParent(event.targetActor, ElementElem.class);
					if (target == null) return;
					JSFunc.copyValue("Element", target.getElement());
				}
			});

			update(() -> {
				if (fixedFocus != null) {
					FOCUS = fixedFocus.getElement();
					FOCUS_WINDOW = this;
					FOCUS_FROM = fixedFocus;
				}
			});
			hidden(CANCEL_TASK);
		}

		public void rebuild(Element element, String text) {
			pattern = PatternUtils.compileRegExpOrNull(text);
			if (element == this.element) return;
			if (element == null) return;

			rebuild(element);
		}

		public void rebuild(Element element) {
			pane.clearChildren();
			pane.left().defaults().left().growX();

			if (element == null) return;
			build(element, pane);
		}

		/** 结构： Label，Image（下划线） */
		public void addMultiRowWithPos(Table table, Element element) {
			elementElem(table, element, () -> Tmp.v1.set(element.x, element.y),
			 t -> t.add(new SearchedLabel(() -> getElementName(element),
				() -> pattern)).style(defaultLabel));
		}

		public void build(Element element, Table table) {
			if (element == null) throw new IllegalArgumentException("element is null");
			if (!DEBUG && element instanceof ReviewElementWindow) {
				table.add(STR."----\{name}-----", defaultLabel).row();
				return;
			}
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
			rebuild(element);
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
	static void elementElem(Table table, Element element, Prov<Vec2> pos, Cons<Table> cons) {
		table.add(new ElementElem(t -> {
			t.left().defaults().left();
			cons.get(t);
			makePosLabel(t, pos);
		}, element)).growX().left().row();
		table.image().color(Tmp.c1.set(JColor.c_underline)).growX().colspan(2).row();
	}

	static void makePosLabel(Table t, Prov<Vec2> pos) {
		if (pos != null) t.label(new PairProv(pos, ", "))
		 .style(defaultLabel).color(Color.lightGray)
		 .fontScale(0.7f).padLeft(4f).padRight(4f);
	}

	public static class ElementElem extends LimitTable {
		public ElementElem(Cons<Table> cons, Element element) {
			cons.get(this);
			userObject = element;
		}
		private ElementElem() { }

		/* private int chunkSize;
		public <T extends Element> Cell<T> add(T element) {
			Cell<T> cell = super.add(element);
			if (!(element instanceof Chunk)) {
				Seq<Cell> cells = getCells();
				if (cells.size - chunkSize > 64) {
					Chunk chunk = new Chunk();
					chunk.getCells().addAll(cells, chunkSize, 64).each(c -> c.setLayout(chunk));
					cells.clear();
					chunkSize++;
					add(chunk);
				}
			}
			return cell;
		}
		private static class Chunk extends Table { } */

		public Element getElement() {
			return (Element) this.userObject;
		}
		public void clear() {
			super.clear();
			userObject = null;
		}
	}
	public static class MyWrapTable extends ElementElem {
		boolean stopEvent, needUpdate;
		ReviewElement.ReviewElementWindow window;

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
			left().defaults().left();

			/* 用于下面的侦听器  */
			int eventChildIndex;
			if (element instanceof Group group) {
				buildForGroup(group);
				eventChildIndex = 1;
			} else {
				defaults().growX();
				eventChildIndex = 0;
				window.addMultiRowWithPos(this, element);
			}
			Element windowElem = this.children.get(eventChildIndex);
			windowElem.touchable = Touchable.enabled;

			// 为图片元素添加预览按钮
			if (element instanceof Image img) {
				PreviewUtils.buildImagePreviewButton(element, (Table) windowElem, img::getDrawable, img::setDrawable);
			}
			touchable = Touchable.enabled;
		}
		public void act(float delta) {
			super.act(delta);
			if (!parentValid(getElement(), window)) remove();
			background(FOCUS_FROM == this ? Styles.flatDown : Styles.none);
		}
		public int getDepth() {
			Element actor = getElement();
			int     depth = 1;
			while (actor != null && actor != window.element) {
				actor = actor.parent;
				depth++;
			}
			return depth;
		}
		private void buildForGroup(Group group) {
			var button   = new FoldedImageButton(true);
			var children = group.getChildren();
			button.setDisabled(children::isEmpty);

			int size = 32;
			add(button).size(size);

			window.addMultiRowWithPos(this, group);

			Element textElement = ((Table) this.children.get(this.children.size - 2)).getChildren().first();

			image().growY().left().update(focusUpdater());

			keyDown(KeyCode.left, () -> button.fireCheck(false));
			keyDown(KeyCode.right, () -> button.fireCheck(true));

			defaults().growX();

			Cons<Table> rebuild = container -> watchChildren(window, group, container, children);

			Table table1 = new LimitTable();
			table1.left().defaults().left().growX();
			boolean expandChildren = maxDepthForAutoExpand.getInt() >= getDepth() &&
			                         (children.size < 20
			                          || group.parent.getChildren().size == 1
			                          || window.element == group);
			button.fireCheck(expandChildren);
			if (expandChildren) rebuild.get(table1);

			button.setContainer(add(table1).grow());
			boolean[] lastEmpty = {children.isEmpty()};
			update(() -> {
				if (children.isEmpty() != lastEmpty[0]) {
					lastEmpty[0] = children.isEmpty();
					HopeFx.changedFx(textElement);
				}
			});

			table1.update(() -> {
				if (needUpdate) {
					button.rebuild.run();
					needUpdate = false;
					return;
				}
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
				if (!parentValid(group, window)) return;
				if (!(needUpdate
				      || ((group.needsLayout() || sizeInvalid(group)) && group.getScene() != null)
				      || !(table1.hasChildren() || !group.hasChildren()))) return;

				rebuild.get(table1);
				HopeFx.changedFx(textElement);
			};
		}
		private Cons<Image> focusUpdater() {
			return t -> t.color.set(FOCUS_FROM == this ? ColorFul.color : Color.darkGray);
		}
		public void clear() {
			super.clear();
			if (FOCUS_FROM == this) CANCEL_TASK.run();
			window = null;
			previousKeyboardFocus = null;
			userObject = null;
			update(null);
		}
		public void removeInternal() {
			super.remove();
		}
		public boolean remove() {
			parentNeedUpdate();
			userObject = null;
			if (FOCUS_FROM == this) CANCEL_TASK.run();
			return super.remove();
		}
		void parentNeedUpdate() {
			MyWrapTable table = ElementUtils.findParent(this, MyWrapTable.class);
			if (table != null) table.needUpdate = true;
		}

		static Prov<Seq<MenuItem>> getContextMenu(ElementElem self, Element element) {
			return () -> ArrayUtils.seq(
			 MenuBuilder.copyAsJSMenu(null, storeRun(() -> element)),
			 ConfirmList.with("clear", Icon.trashSmall, "@clear", "@confirm.remove", () -> {
				 self.remove();
				 element.remove();
			 }),
			 MenuItem.with("path.copy", Icon.copySmall, "@copy.path", () -> {
				 JSFunc.copyText(ElementUtils.getPath(element));
			 }),
			 MenuItem.with("screenshot", Icon.fileImageSmall, "@reviewElement.screenshot", () -> {
				 ElementUtils.quietScreenshot(element);
			 }),
			 CheckboxList.withc("debug.bounds", Icon.adminSmall, "@settings.debugbounds",
				() -> TopGroup.getDrawPadElem() == element, () -> REVIEW_ELEMENT.toggleDrawPadElem(element)),
			 MenuItem.with("window.new", Icon.copySmall, "New Window", () -> new ReviewElementWindow().show(element)),
			 MenuItem.with("details", Icon.infoSmall, "@details", () -> INFO_DIALOG.showInfo(element)),
			 FoldedList.withf("exec", Icon.boxSmall, "Exec", () -> execChildren(element)),
			 ValueLabel.newElementDetailsList(element),

			 element instanceof Table ?
				MenuItem.with("allcells", Icon.wavesSmall, "All Cells", () -> viewAllCells((Table) element)) : null,

			 element != null && element.parent instanceof Table ?
				DisabledList.withd("this.cell", Icon.wavesSmall, "This Cell",
				 () -> element != null && !(element.parent instanceof Table && ((Table) element.parent).getCell(element) != null), () -> {
					 new CellDetailsWindow(((Table) element.parent).getCell(element)).show();
				 }) : null);
		}

		private static Seq<MenuItem> execChildren(Element element) {
			return Seq.with(
			 element instanceof Table table ? MenuItem.with("background", Icon.boxSmall, "Set Background", () -> {
				 IntUI.drawablePicker().show(table.getBackground(), table::setBackground);
			 }) : null,
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
	}
	private static boolean sizeInvalid(Group group) {
		return group instanceof Table && UNSAFE.getBoolean(group, TABLE.sizeInvalid);
	}

	private static Window viewAllCells(Table element) {
		class AllCellsWindow extends Window implements IDisposable {
			public AllCellsWindow() { super("All Cells"); }
		}

		class CellItem extends Table implements CellView {
			public CellItem(Drawable background, Cons<Table> cons) {
				super(background, cons);
			}
		}

		Window d         = new AllCellsWindow();
		Table  container = new LimitTable();
		d.cont.pane(container).grow();
		container.left().defaults().left();
		for (var cell : element.getCells()) {
			container.add(new CellItem(Tex.pane, t0 -> {
				 var l = new PlainValueLabel<>(Cell.class, () -> cell);
				 ReviewElement.addFocusSource(t0, () -> d, cell::get);
				 t0.add(l).grow();
			 })).grow()
			 .colspan(CellTools.colspan(cell));
			if (cell.isEndRow()) {
				Underline.of(container.row(), 20);
			}
		}
		d.update(d::display);
		return d;
	}


	static boolean parentValid(Element element, ReviewElement.ReviewElementWindow window) {
		return element.parent != null || element == window.element;
	}

	/** 监视children的变化 */
	private static void watchChildren(ReviewElementWindow window, Group group, Table container,
	                                  SnapshotSeq<Element> children) {
		if (!container.hasChildren()) {
			for (Element child : children) {
				if (window.wrapCache != null && window.wrapCache.getElement() == child) {
					container.add(window.wrapCache).row();
					continue;
				}
				window.build(child, container);
			}
			return;
		}
		Cell<?>[] cells = new Cell<?>[children.size];
		for (Element item : container.getChildren()) {
			if (item instanceof MyWrapTable wrapTable) {
				Element data = wrapTable.getElement();
				if (data == null || data.parent != group) continue;
				int index = data.getZIndex();
				if (index == -1) continue;

				Cell cell = container.getCell(item);
				cells[index] = cell;
			}
		}
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
			ModifiableLabel.build(def, NumberHelper::isFloat, (field, label) -> {
				if (!field.isValid()) return;
				label.setText(field.getText());
				floatc.get(NumberHelper.asFloat(field.getText()));
			}, t, AutoTextField::new);
		});
	}


	public enum Settings implements ISettings {
		hoverInfoWindow/* , contextMenu(MenuItem[].class, MyWrapTable.getContextMenu(null, null, null)) */,
		/** （显示名称时）匿名类而不是非匿名超类 */
		anonymousInsteadSuper,
		/** 判断是否可见 */
		checkCullingArea,
		/**
		 * 最大自动展开深度
		 * @see ISettings#$(Integer)
		 */
		maxDepthForAutoExpand(int.class, 5/* def */, 0/* min */, 50/* max */, 1/* step */),
		//
		;

		Settings() { }
		Settings(Class<?> a, int... args) { }
		Settings(Class<?> a, Prov<Seq<MenuItem>> prov) { }
	}

	public static class InfoDetails extends Table implements KeyValue {
		Label nameLabel = new VLabel(0.85f, KeyValue.stressColor),
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

		BindCell visibleCell, rotCell, translationCell, styleCell, alignCell,
		 cellCell,
		 colspanCell, minSizeCell, maxSizeCell,
		 fillCell, expandCell;

		final Vec2 sizeVec = new Vec2();
		SizeProv sizeProv = new SizeProv(() -> sizeVec, " × ");
		void size(float width, float height) {
			sizeVec.set(width, height);
		}
		void touchableF(Touchable touchable) {
			touchableLabel.setText(FormatHelper.touchable(touchable));
			touchableLabel.setColor(touchableToColor(touchable));
		}
		/** @param bool element.visible */
		void visible(boolean bool) {
			visibleCell.toggle(!bool);
		}
		void color(Color color) {
			colorContainer.setColorValue(color);
			String string = FormatHelper.color(color);
			colorLabel.setText(color.a == 1 ? string.substring(0, 6) : string);
		}
		void rotation(float rotation) {
			if (rotCell.toggle1(rotation % 360 != 0))
				rotationLabel.setText(fixed(rotation));
		}
		void translation(Vec2 translation) {
			if (translationCell.toggle1(!Mathf.zero(translation.x) || !Mathf.zero(translation.y)))
				translationLabel.setText(STR."\{fixed(translation.x)}, \{fixed(translation.y)}");
		}
		void style(Element element) {
			try {
				Style style = (Style) element.getClass().getMethod("getStyle", (Class<?>[]) null).invoke(element, (Object[]) null);
				if (styleCell.toggle1(style != null && ShowUIList.styleKeyMap.containsKey(style)))
					styleLabel.setText(FormatHelper.fieldFormat(ShowUIList.styleKeyMap.get(style)));
			} catch (Throwable e) { styleCell.remove(); }
		}
		void align(Element element) {
			if (alignCell.toggle1(element instanceof Table))
				alignLabel.setText(FormatHelper.align(((Table) element).getAlign()));
		}
		void colspan(Cell<?> cell) {
			if (cell == null) {
				colspanCell.remove();
				return;
			}
			int colspan = CellTools.colspan(cell);
			if (colspanCell.toggle1(colspan != 1))
				colspanLabel.setText("" + colspan);
		}
		final Vec2 minSizeVec = new Vec2(), maxSizeVec = new Vec2();
		SizeProv minSizeProv = new SizeProv(() -> minSizeVec.scl(1 / Scl.scl()));
		SizeProv maxSizeProv = new SizeProv(() -> maxSizeVec.scl(1 / Scl.scl()));
		void minSize(Cell<?> cell) {
			if (cell == null) {
				minSizeCell.remove();
				return;
			}
			float minWidth = CellTools.minWidth(cell),
			 minHeight = CellTools.minHeight(cell);
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
			float maxWidth = CellTools.maxWidth(cell),
			 maxHeight = CellTools.maxHeight(cell);
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
			float fillX = CellTools.fillX(cell),
			 fillY = CellTools.fillY(cell);
			if (fillCell.toggle1(fillX != 0 || fillY != 0))
				fillLabel.setText(STR."\{enabledMark((int) fillX)}x []| \{enabledMark((int) fillY)}y");
		}
		void expand(Cell<?> cell) {
			if (cell == null) {
				expandCell.remove();
				return;
			}
			int expandX = CellTools.expandX(cell),
			 expandY = CellTools.expandY(cell);
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

		void setPosition(Element elem) {
			// 初始在元素的左上角
			IntUI.positionTooltip(elem, Align.topLeft, this, Align.bottomLeft);
		}
		private void build(Table t) {
			t.defaults().growX();
			t.table(top -> {
				top.add(nameLabel).padLeft(-4f);
				visibleCell = BindCell.ofConst(top.image(Icon.eyeOffSmall)
				 .color(Pal.accent)
				 .size(16f).pad(4, 8, 4, 4));

				sizeLabel.setText(sizeProv);
				top.add(sizeLabel).padLeft(10f)
				 .right().labelAlign(Align.right)
				 .growX();
			});
			t.row().table(tableCons("Touchable", touchableLabel));
			t.row().table(tableCons("Color", color -> {
				color.add(colorContainer).size(16).padRight(4f);
				color.add(colorLabel);
			}));
			rotCell = buildKey(t, "Rotation", rotationLabel);
			translationCell = buildKey(t, "Translation", translationLabel);
			styleCell = buildKey(t, "Style", styleLabel);
			alignCell = buildKey(t, "Align", alignLabel);
			cellCell = makeCell(t, _ -> {
				colspanCell = buildKey(t, "Colspan", colspanLabel);
				minSizeCell = buildKey(t, "MinSize", minSizeLabel);
				maxSizeCell = buildKey(t, "MaxSize", maxSizeLabel);
				expandCell = buildKey(t, "Expand", expandLabel);
				fillCell = buildKey(t, "Fill", fillLabel);
			});
		}
		private BindCell buildKey(Table t, String key, Label label) {
			return makeCell(t, tableCons(key, label));
		}
		private BindCell makeCell(Table t, Cons<Table> cons) {
			return BindCell.ofConst(t.row().table(cons).growX());
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

			table.nameLabel.setText(getElementName(elem));
			table.size(elem.getWidth(), elem.getHeight());
			table.touchableF(elem.touchable);
			table.visible(elem.visible);
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
			table.pack();
			table.act(0);
			table.setPosition(elem);
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

	/** 如果source元素有CellView接口，drawFocus按照下面来 */
	public interface CellView {
		default void drawFocus(Element focus) {
			if (focus == null || !(focus.parent instanceof Table table)) return;
			drawFocus(table.getCell(focus), focus);
		}
		default void drawFocus(Cell<?> cl, Element focus) {
			int   column = CellTools.column(cl);
			int   row    = CellTools.row(cl);
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
			 padBottom = CellTools.padBottom(cl),
			 padLeft = CellTools.padLeft(cl),
			 padRight = CellTools.padRight(cl);
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