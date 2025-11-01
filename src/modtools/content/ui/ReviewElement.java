package modtools.content.ui;

import arc.Core;
import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.style.Style;
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
import modtools.content.SettingsUI.SettingsBuilder;
import modtools.events.ISettings;
import modtools.jsfunc.*;
import modtools.jsfunc.reflect.UNSAFE;
import modtools.misc.PairProv;
import modtools.misc.PairProv.SizeProv;
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
import modtools.ui.comp.utils.ValueLabel;
import modtools.ui.control.HKeyCode;
import modtools.ui.effect.*;
import modtools.ui.gen.HopeIcons;
import modtools.ui.menu.*;
import modtools.utils.*;
import modtools.utils.EventHelper.DoubleClick;
import modtools.utils.MySettings.Data;
import modtools.utils.reflect.FieldUtils;
import modtools.utils.search.BindCell;
import modtools.utils.ui.*;
import modtools.utils.ui.LerpFun.DrawExecutor;
import modtools.utils.ui.ReflectTools.MarkedCode;

import java.lang.reflect.Field;
import java.util.regex.Pattern;

import static arc.Core.scene;
import static modtools.IntVars.mouseVec;
import static modtools.content.ui.ReviewElement.Settings.*;
import static modtools.ui.HopeStyles.defaultLabel;
import static modtools.ui.IntUI.*;
import static modtools.utils.ui.CellTools.unset;
import static modtools.utils.ui.FormatHelper.fixed;

/**
 * Ctrl+Shift+C审查元素
 * @author I-hope1
 */
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

	public static final boolean DEBUG = false;


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
					/*  && source.getListeners().find(t -> this.getClass().isInstance(t)) == null */) { return; }
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
		Contents.settings_ui.addSection(localizedName(), icon, table -> {
			table.left().defaults().left();
			table.table(t -> {
				ISettings.buildAll("", t, Settings.class);
				settingColor(t.table().growX().get());
			}).grow();
		});
	}

	/** 代码生成{@code ColorProcessor} */
	public void settingColor(Table t) { }


	public HKeyCode inspectKeycode     =
	 keyCodeData().dynamicKeyCode("inspect", () -> new HKeyCode(KeyCode.c).ctrl().shift())
		.applyToScene(true, this::build0);
	public HKeyCode debugBoundsKeyCode =
	 keyCodeData().dynamicKeyCode("debugBounds", () -> new HKeyCode(KeyCode.d).ctrl().alt())
		.applyToScene(true, TSettings.debugBounds::toggle);

	public HKeyCode selectDebugBoundsKeyCode =
	 keyCodeData().dynamicKeyCode("selectDebugBounds", () -> new HKeyCode(KeyCode.d).ctrl().alt().shift())
		.applyToScene(true, () -> {
			if (topGroup.isSelecting()) return;

			TSettings.debugBounds.set(true);
			topGroup.requestSelectElem(TopGroup.defaultDrawer, TopGroup::setDrawPadElem);
		});

	public HKeyCode nextSearchKeyCode =
	 keyCodeData().dynamicKeyCode("nextSearch", () -> new HKeyCode(KeyCode.f3))
		.applyToScene(true, () -> {
			if (FOCUS_WINDOW instanceof ReviewElementWindow window) window.nextSearch();
		});
	public HKeyCode prevSearchKeyCode =
	 keyCodeData().dynamicKeyCode("prevSearch", () -> new HKeyCode(KeyCode.f3).shift())
		.applyToScene(true, () -> {
			if (FOCUS_WINDOW instanceof ReviewElementWindow window) window.nextSearch();
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
		btn.addListener(new ITooltip(() -> tipKey("shortcuts", inspectKeycode.getText())));
		TopGroup.searchBlackList.add(btn);
		return btn;
	}
	//region draw
	public static void drawPadding(Element elem, Vec2 vec2, Table table) {
		/* 如果a = 0就返回 */
		if (checkA(padColor)) return;
		Draw.color(padColor);
		Cell<?> cl = table.getCell(elem);
		if (cl != null) {
			drawPadding(elem, vec2, cl);
		}
		Draw.color();
	}
	public static void drawPadding(Element elem, Vec2 vec2, Cell<?> cl) {
		float padLeft = CellTools.padLeft(cl),
		 padTop = CellTools.padTop(cl),
		 padBottom = CellTools.padBottom(cl),
		 padRight = CellTools.padRight(cl);

		drawMarginOrPad(vec2, elem, true, padLeft, padTop, padRight, padBottom);
	}
	/** 检查a(后两位)是否为0 */
	static boolean checkA(int color) {
		return (color & 0xFF) == 0;
	}

	public static void drawMargin(Vec2 vec2, Table table) {
		if (checkA(marginColor)) return;
		Draw.color(marginColor);

		drawMarginOrPad(vec2, table, false,
		 table.getMarginLeft(), table.getMarginTop(),
		 table.getMarginRight(), table.getMarginBottom());

		Draw.color();
	}
	/**
	 * 绘制外边距(Margin)或内边距(Padding)的可视化指示器。
	 * @param vec2   元素左上角坐标
	 * @param elem   目标元素
	 * @param pad    true 表示绘制内边距 (Padding), false 表示绘制外边距 (Margin)
	 * @param left   左边距/填充值
	 * @param top    上边距/填充值
	 * @param right  右边距/填充值
	 * @param bottom 底边距/填充值
	 */
	private static void drawMarginOrPad(
	 Vec2 vec2, Element elem, boolean pad,
	 float left, float top, float right, float bottom) {

		// 数据预处理和初始化
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

		final Color color = pad ? Tmp.c1.set(padTextColor) : Tmp.c1.set(marginTextColor);
		final float mul   = pad ? -1 : 1;

		final float elemX = vec2.x;
		final float elemY = vec2.y;
		final float elemW = elem.getWidth();
		final float elemH = elem.getHeight();
		final float fontH = MyDraw.fontHeight();

		// 调用统一的辅助方法来绘制各个边
		// 左边 (Left)
		drawSideIndicator(left, mul, color,
		 elemX, elemY, left, elemH,
		 elemX + left / 2f, elemY + (fontH + elemH) / 2f);

		// 底部 (Bottom)
		drawSideIndicator(bottom, mul, color,
		 elemX, elemY, elemW, bottom,
		 elemX + elemW / 2f, elemY + bottom);

		// 顶部 (Top)
		drawSideIndicator(top, mul, color,
		 elemX, elemY + elemH, elemW, -top,
		 elemX + elemW / 2f, elemY + elemH - top / 2f);

		// 右边 (Right) - 注意：修复了原始代码中可能存在的bug
		drawSideIndicator(right, mul, color,
		 elemX + elemW, elemY, -right, elemH,
		 elemX + elemW - right / 2f, elemY + (fontH + elemH) / 2f);
	}

	/**
	 * 绘制单个边的指示器（矩形和文本）。
	 * @param value          边距/填充的原始值 (用于判断是否为0)
	 * @param textMultiplier 用于显示的文本值的乘数 (padding为-1, margin为1)
	 * @param color          文本颜色
	 * @param rectX          矩形X坐标
	 * @param rectY          矩形Y坐标
	 * @param rectW          矩形宽度
	 * @param rectH          矩形高度
	 * @param textX          文本X坐标
	 * @param textY          文本Y坐标
	 */
	private static void drawSideIndicator(float value, float textMultiplier, Color color,
	                                      float rectX, float rectY, float rectW, float rectH,
	                                      float textX, float textY) {
		// 使用卫语句 (guard clause) 提前返回，使代码更扁平
		if (value == 0) {
			return;
		}

		Fill.crect(rectX, rectY, rectW, rectH);
		MyDraw.drawText(fixed(value * textMultiplier), textX, textY, color);
	}
	/** 从元素到hover的元素的连线 */
	public static void drawLine() {
		if (FOCUS == null) return;

		Vec2 vec2 = ElementUtils.getAbsPosCenter(FOCUS);
		Draw.color(ColorFul.color);
		Lines.stroke(3f);
		Lines.line(mouseVec.x, mouseVec.y, vec2.x, vec2.y);
		Draw.color();
	}
	//endregion

	public final Cons<Element> callback = selected -> new ReviewElementWindow().show(selected);
	public void build() {
		if (topGroup.isSelecting()) { topGroup.resetSelectElem(); } else topGroup.requestSelectElem(null, callback);
	}


	public static final Runnable CANCEL_TASK = () -> {
		FOCUS = null;
		FOCUS_WINDOW = null;
		FOCUS_FROM = null;
	};

	public ReviewElementWindow inspect(Element element) {
		ReviewElementWindow window = new ReviewElementWindow();
		window.show(element);
		return window;
	}

	public class ReviewElementWindow extends IconWindow implements IDisposable, DrawExecutor {
		public boolean drawCell;
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
			super(20, 160, true);

			name = "ReviewElementWindow";

			pane.top().left().defaults().left().top();
			cont.table(t -> {
				t.defaults().size(35).padLeft(3f);
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
					 } else { go.run(); }
				 })
				 .disabled(_ -> element == null || element.parent == null)
				 .with(b -> b.getLabel().setFontScale(0.9f))
				 .size(130, 35).get();

				t.button(Icon.copySmall, HopeStyles.clearNonei, 28, () -> {
					var window = new ReviewElementWindow();
					window.pattern = pattern;
					window.show(element);
					window.shown(() -> window.setSize(width, height));
				});
				t.button(Icon.refreshSmall, HopeStyles.clearNonei, 28, () -> rebuild(element));
				t.button(Icon.settingsSmall, HopeStyles.clearNonei, 28, () -> {
					showSelectTableRB((p, _, _) -> {
						p.background(Tex.pane);
						SettingsBuilder.build(p);
						SettingsBuilder.check("Draw Cell", b -> drawCell = b, () -> drawCell);
						Underline.of(p, 1);
						ISettings.buildAll("", p, Settings.class);
						// SettingsBuilder.check("Expand All", b -> drawCell = b, () -> drawCell);
						SettingsBuilder.clearBuild();
					}, false);
				});
				t.defaults().size(unset);
				t.table(search -> {
					search.image(Icon.zoomSmall).size(35);
					search.field("", str -> rebuild(element, str, 0))
					 .with(f -> f.setMessageText("@players.search"))
					 .with(f -> f.addListener(new HoverAndExitListener() {
						 public void enter0(InputEvent event, float x, float y, int pointer, Element fromActor) {
							 FOCUS_WINDOW = ReviewElementWindow.this;
						 }
						 public void exit0(InputEvent event, float x, float y, int pointer, Element toActor) {
							 FOCUS_WINDOW = null;
						 }
					 }))
					 .with(f -> ReviewElementWindow.this.addCaptureListener(new FocusSearchListener(f)))
					 .growX();
				}).growX();
			}).growX().row();

			cont.add(new ScrollPane(pane, Styles.smallPane) {
				public String toString() {
					return DEBUG ? super.toString() : name;
				}
			}).grow().minHeight(120);

			MenuBuilder.addShowMenuListenerp(pane, ElementElem.class, target -> getContextMenu(target, target.getElement()));
			var keyMap = getKeyMap();

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
		public ObjectMap<KeyCode, Cons<ElementElem>> getKeyMap() {
			ObjectMap<KeyCode, Cons<ElementElem>> keyMap = new ObjectMap<>();
			keyMap.put(KeyCode.f, e -> fixedFocus = fixedFocus == e ? null : e);
			keyMap.put(KeyCode.i, e -> INFO_DIALOG.showInfo(e.getElement()));
			keyMap.put(KeyCode.r, e -> MenuBuilder.showMenuList(execChildren(e.getElement())));
			keyMap.put(KeyCode.del, e -> shiftIgnoreConfirm(getElementName(e.getElement()), () -> {
				e.getElement().remove();
				e.remove();
			}));
			keyMap.put(KeyCode.p, e -> {
				if (e.getElement() instanceof Image img) {
					drawablePicker().show(img.getDrawable(), true, img::setDrawable);
				}
			});
			keyMap.put(KeyCode.c, e -> {
				Element elem = e.getElement();
				if (CellDetailsWindow.valid(elem)) {
					new CellDetailsWindow(((Table) elem.parent).getCell(elem));
				}
			});
			keyMap.put(KeyCode.left, e -> {
				if (e instanceof MyWrapTable table && table.rebuildGroup != null) table.rebuildGroup.get(false);
			});
			keyMap.put(KeyCode.right, e -> {
				if (e instanceof MyWrapTable table && table.rebuildGroup != null) table.rebuildGroup.get(true);
			});
			return keyMap;
		}

		public void rebuild(Element element, String text) {
			rebuild(element, text, 0);
		}
		private Runnable nextSearch;
		public void nextSearch() {
			if (nextSearch != null) nextSearch.run();
		}
		public void rebuild(Element element, String text, int searchIndex) {
			pattern = PatternUtils.compileRegExpOrNull(text);
			if (element == this.element) {
				scrollToSearch(text, searchIndex);
				return;
			}
			if (element == null) return;

			rebuild(element);
		}
		private void scrollToSearch(String text, int searchIndex) {
			if (autoScrollToSearch.enabled() && pattern != null) {
				nextSearch = () -> rebuild(element, text, searchIndex - Mathf.sign(Core.input.shift()));
				int[]   ints   = {searchIndex};
				Element target = find(e -> e instanceof SearchedLabel l && l.hasHighlight(pattern) && ints[0]-- == 0);
				if (target != null) ElementUtils.scrollTo(pane, target);
			}
		}

		public void rebuild(Element element) {
			pane.clearChildren();
			pane.left().defaults().left().growX();

			if (element == null) return;
			build(element, pane);
		}

		/** 结构： Label，Image（下划线） */
		public void addMultiRowWithPos(MyWrapTable table, Element element) {
			elementElem(table, element, () -> Tmp.v1.set(element.x, element.y),
			 t -> t.add(new SearchedLabel(() -> getElementName(element),
				() -> pattern)).style(defaultLabel));
		}

		public void build(Element element, Table table) {
			if (element == null) throw new IllegalArgumentException("element is null");
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
		}, element)).growX().left();
		Underline.of(table, 2);
	}

	static void makePosLabel(Table t, Prov<Vec2> pos) {
		if (pos != null) {
			t.label(new PairProv(pos, ", "))
			 .style(defaultLabel).color(Color.lightGray)
			 .fontScale(0.7f).padLeft(4f).padRight(4f);
		}
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
	public class MyWrapTable extends ElementElem {
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
			left().defaults().left();

			/* 用于下面的侦听器  */
			int eventChildIndex;
			if (!DEBUG && element instanceof ReviewElementWindow) {
				add(STR."!!!\{element.name}", defaultLabel).row();
				eventChildIndex = 0;
			} else if (element instanceof Group group) {
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
			background(FOCUS_FROM == this ? Styles.flatDown : noneui);
		}
		public static final Vec2 bgVec = new Vec2();
		/**
		 * TODO: 位置bug
		 * @see Table#drawBackground(float, float)
		 */
		protected void drawBackground(float x, float y) {
			ScrollPane pane = ElementUtils.findClosestPane(this);
			if (pane == null || true) {
				super.drawBackground(x, y);
				return;
			}
			// pane的左下角坐标
			pane.localToDescendantCoordinates(this, bgVec.set(0, 0));
			float lastW = width, lastH = height;
			float x1    = Mathf.clamp(x, bgVec.x, bgVec.x + pane.getWidth());
			float y1    = Mathf.clamp(y, bgVec.y, bgVec.y + pane.getHeight());
			float x2    = Mathf.clamp(x + width, bgVec.x, bgVec.x + pane.getWidth());
			float y2    = Mathf.clamp(y + height, bgVec.y, bgVec.y + pane.getHeight());
			width = x2 - x1;
			height -= y2 - y1;
			super.drawBackground(x1, y1);
			width = lastW;
			height = lastH;
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
		Boolc rebuildGroup;
		private void buildForGroup(Group group) {
			var button   = new FoldedImageButton(true);
			var children = group.getChildren();
			button.setDisabled(children::isEmpty);

			int size = 32;
			add(button).size(size);

			window.addMultiRowWithPos(this, group);

			Element textElement = ((Table) this.children.get(this.children.size - 2)).getChildren().first();

			image().growY().left().update(focusUpdater());

			rebuildGroup = button::fireCheck;

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
				      || !(table1.hasChildren() || !group.hasChildren()))) { return; }
				HopeFx.changedFx(textElement);

				if (!button.isChecked()) return;
				rebuild.get(table1);
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
	}

	Prov<Seq<MenuItem>> getContextMenu(ElementElem self, Element element) {
		return () -> ArrayUtils.seq(
		 MenuItem.with("path.copy", Icon.copySmall, "@copy.path", () -> {
			 JSFunc.copyText(ElementUtils.getPath(element));
		 }),
		 MenuItem.with("screenshot", Icon.fileImageSmall, "@reviewElement.screenshot", () -> {
			 Time.runTask(2, () -> ElementUtils.quietScreenshot(element));
		 }),

		 UnderlineItem.with(),

		 MenuBuilder.copyAsJSMenu(null, storeRun(() -> element)),
		 ConfirmList.with("clear", Icon.trashSmall, "@element.remove", "@confirm.remove", () -> {
			 self.remove();
			 element.remove();
		 }),
		 CheckboxList.withc("debug.bounds", Icon.adminSmall, "@settings.debugbounds",
			() -> TopGroup.getDrawPadElem() == element, () -> REVIEW_ELEMENT.toggleDrawPadElem(element)),
		 MenuItem.with("window.new", Icon.copySmall, "New Window", () -> new ReviewElementWindow().show(element)),
		 MenuItem.with("details", Icon.infoSmall, "@details", () -> INFO_DIALOG.showInfo(element)),
		 FoldedList.withf("exec", Icon.boxSmall, "Exec", () -> execChildren(element)),
		 ValueLabel.newElementDetailsList(element),

		 UnderlineItem.with(),

		 element instanceof Table ?
			MenuItem.with("allcells", Icon.wavesSmall, "All Cells", () -> viewAllCells((Table) element)) : null,

		 element != null && element.parent instanceof Table ?
			DisabledList.withd("this.cell", Icon.wavesSmall, "This Cell",
			 () -> !CellDetailsWindow.valid(element), () -> {
				 new CellDetailsWindow(((Table) element.parent).getCell(element));
			 }) : null);
	}

	static Seq<MenuItem> execChildren(Element element) {
		Seq<MenuItem> baseSeq = Seq.with(
		 MenuItem.with("invalidate", Icon.boxSmall, "Invalidate", element::invalidate),
		 MenuItem.with("invalidateHierarchy", Icon.boxSmall, "InvalidateHierarchy", element::invalidateHierarchy),
		 MenuItem.with("layout", Icon.boxSmall, "Layout", element::layout),
		 MenuItem.with("pack", Icon.boxSmall, "Pack", element::pack),
		 MenuItem.with("validate", Icon.boxSmall, "Validate", element::validate),
		 MenuItem.with("keepInStage", Icon.boxSmall, "Keep in stage", element::keepInStage),
		 MenuItem.with("toFront", Icon.boxSmall, "To Front", element::toFront),
		 MenuItem.with("toBack", Icon.boxSmall, "To Back", element::toBack),

		 UnderlineItem.with()
		);
		SR.apply(() -> SR.of(element)
		 .isInstance(Table.class, table -> baseSeq.addAll(
			MenuItem.with("background", Icon.boxSmall, "Set Background", () -> {
				drawablePicker().show(table.getBackground(), table::setBackground);
			}),
			MenuItem.with("table.center", Icon.boxSmall, "Table Center", l(table, Align.center)),
			MenuItem.with("table.left", Icon.boxSmall, "Table Left", l(table, Align.left)),
			MenuItem.with("table.right", Icon.boxSmall, "Table Right", l(table, Align.right)),
			MenuItem.with("table.top", Icon.boxSmall, "Table Top", l(table, Align.top)),
			MenuItem.with("table.bottom", Icon.boxSmall, "Table Bottom", l(table, Align.bottom))
		 ))
		 .isInstance(Label.class, label -> baseSeq.addAll(
			MenuItem.with("label.copy", Icon.boxSmall, "Copy Text", () -> JSFunc.copyText(label.getText())),
			MenuItem.with("label.center", Icon.boxSmall, "Label Center", l(label, Align.center)),
			MenuItem.with("label.left", Icon.boxSmall, "Label Left", l(label, Align.left)),
			MenuItem.with("label.right", Icon.boxSmall, "Label Right", l(label, Align.right)),
			MenuItem.with("label.top", Icon.boxSmall, "Label Top", l(label, Align.top)),
			MenuItem.with("label.bottom", Icon.boxSmall, "Label Bottom", l(label, Align.bottom))
		 ))
		 .isInstance(Dialog.class, dialog -> baseSeq.addAll(
			MenuItem.with("dialog.hide", Icon.boxSmall, "Hide", () -> dialog.hide()),
			MenuItem.with("dialog.show", Icon.boxSmall, "Show", () -> dialog.show())
		 ))
		);
		return baseSeq;
	}
	private static Runnable l(Label l, int align) {
		return () -> l.setAlignment(align);
	}
	private static Runnable l(Table t, int align) {
		return () -> {
			t.align(align);
			t.layout();
		};
	}
	private static boolean sizeInvalid(Group group) {
		return group instanceof Table && UNSAFE.getBoolean(group, TABLE.sizeInvalid);
	}


	private static Window viewAllCells(Table table) {
		return new AllCellsWindow(table);
	}


	static boolean parentValid(Element element, ReviewElementWindow window) {
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
			if (name != null) { t.add(name).color(Pal.accent).fontScale(0.8f).padRight(8f); }
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
		 * @see ISettings#$(int, int, int, int)
		 */
		maxDepthForAutoExpand(int.class, it -> it.$(5/* def */, 0/* min */, 50/* max */, 1/* step */)),

		/** 自动滚动到搜索位置 */
		autoScrollToSearch,
		//
		;
		static {
			hoverInfoWindow.defTrue();
			anonymousInsteadSuper.defTrue();
		}

		Settings() { }
		Settings(Class<?> a, Cons<ISettings> builder) { }
	}

	// ========================================================================
	// 1. 核心抽象：DetailEntry 接口
	// ========================================================================

	/**
	 * 代表信息面板中的一个可管理的条目。
	 * 每个条目封装了自己的UI、更新逻辑和可见性规则。
	 */
	interface DetailEntry {
		/**
		 * 构建并添加此条目的UI组件到父表格中。
		 * 此方法只在初始化时调用一次。
		 * @param table 父表格
		 */
		void build(Table table);

		/**
		 * 根据给定的元素更新此条目的UI状态（值和可见性）。
		 * @param element 当前聚焦的元素
		 */
		void update(Element element);
		boolean valid(Element element);
	}


	// ========================================================================
	// 2. 重构后的 InfoDetails 类
	// ========================================================================

	/**
	 * InfoDetails 现在是一个高层次的协调者。
	 * 它管理一个DetailEntry列表，并委托所有构建和更新任务。
	 */
	public static class InfoDetails {
		private final Seq<DetailEntry> entries = new Seq<>();

		public InfoDetails() {
			// 按顺序创建和注册所有信息条目
			// 这种方式使得添加、删除或重新排序条目变得非常简单。
			entries.add(new HeaderEntry());
			entries.add(new SimpleEntry("Touchable", e -> FormatHelper.touchable(e.touchable), e -> touchableToColor(e.touchable)));
			entries.add(new ColorEntry());
			entries.add(new BoolEntry("Transform", e -> e instanceof Group g && g.isTransform()));
			entries.add(new ElemFieldEntry("rotation"));
			entries.add(new ElemFieldEntry(Element.class, "translation", new PairCons(", ")));
			entries.add(new RotationEntry());
			entries.add(new StyleEntry());
			entries.add(new AlignEntry<>("Align", Table.class, Table::getAlign));
			entries.add(new ElemScaleEntry());

			// Cell 相关条目
			entries.add(new SeparatorEntry(4, 2, 4, 2));
			entries.add(new CellAlignEntry());
			entries.add(new ColspanEntry());
			entries.add(new SizePairEntry("MinSize", CellTools::minSize, cell -> CellTools.minWidth(cell) != 0 || CellTools.minHeight(cell) != 0));
			entries.add(new SizePairEntry("MaxSize", CellTools::maxSize, cell -> CellTools.maxWidth(cell) != 0 || CellTools.maxHeight(cell) != 0));
			entries.add(new BoolPairEntry<Cell<?>>("Expand", ProviderType.cell, cell -> Tmp.v1.set(CellTools.expandX(cell), CellTools.expandY(cell))));
			entries.add(new BoolPairEntry<Cell<?>>("Fill", ProviderType.cell, cell -> Tmp.v1.set(CellTools.fillX(cell), CellTools.fillY(cell))));
			entries.add(new BoolPairEntry<Cell<?>>("Uniform", ProviderType.cell, cell -> Tmp.v1.set(CellTools.uniformX(cell) ? 1 : 0, CellTools.uniformY(cell) ? 1 : 0)));

			// Label 相关条目
			entries.add(new SeparatorEntry(4, 2, 4, 2));
			entries.add(new AlignEntry<>("LabelAlign", Label.class, Label::getLabelAlign));
			entries.add(new AlignEntry<>("LineAlign", Label.class, Label::getLineAlign));
			entries.add(new LabelWrapEntry());
			entries.add(new ScalingEntry());

			// ScrollPane 相关条目
			entries.add(new SeparatorEntry(4, 2, 4, 2));
			entries.add(new BoolPairEntry<ScrollPane>("Scroll", ProviderType.scroll, scroll -> Tmp.v1.set(scroll.isScrollX() ? 1 : 0, scroll.isScrollY() ? 1 : 0)));
		}

		/**
		 * 构建整个信息面板的UI。现在是一个简单的循环。
		 */
		public void build(Table t) {
			t.background(Tex.pane);
			t.defaults().growX();
			for (DetailEntry entry : entries) {
				entry.build(t);
			}
		}

		/**
		 * 更新所有条目的状态。
		 */
		public void updateFor(Element element) {
			for (DetailEntry entry : entries) {
				if (entry.valid(element)) {
					entry.update(element);
				}
			}
		}

		/**
		 * 定位逻辑保持不变。
		 */
		public void setPosition(Element elem, Table table) {
			positionTooltip(elem, Align.topLeft, table, Align.bottomLeft);
		}
		private static class PairCons implements Cons2<Label, Object> {
			final String delim;
			public PairCons(String delims) {
				this.delim = delims;
				prov = new PairProv(() -> vec2, delim, true);
			}
			final Vec2     vec2 = new Vec2();
			final PairProv prov;
			public void get(Label l, Object v) {
				vec2.set((Position) v);
				l.setText(prov.get());
			}
		}
	}


	// ========================================================================
	// 3. 重构后的 ReviewFocusTask 类
	// ========================================================================

	/**
	 * ReviewFocusTask 现在职责清晰：
	 * - 绘制焦点视觉效果。
	 * - 在焦点改变时，触发InfoDetails进行一次完整的更新。
	 * - 每帧绘制已经更新和布局好的信息面板。
	 */
	class ReviewFocusTask extends FocusTask {
		{ drawSlightly = true; }

		final   InfoDetails info               = new InfoDetails();
		final   Table       table              = new Table();
		private Element     lastFocusedElement = null;

		{
			// 构建UI的操作只在初始化时执行一次
			info.build(table.table().pad(4).get());
		}

		public ReviewFocusTask() { super(ReviewElement.MASK_COLOR, ReviewElement.FOCUS_COLOR); }
		/** 清除elemDraw */
		public void elemDraw() { }
		@Override
		public void beforeDraw(Window drawer) {
			if (drawer == FOCUS_WINDOW && FOCUS != null) {
				if (FOCUS_FROM instanceof CellView cw) {
					cw.drawFocus(FOCUS);
					// Vec2 pos = ElementUtils.getAbsolutePos(FOCUS);
					// super.drawFocus(FOCUS, pos);
					// MyDraw.intoDraw(() -> drawGeneric(FOCUS, pos));
				} else {
					drawFocus(FOCUS);
				}
			}
		}
		@Override
		public void drawFocus(Element elem, Vec2 pos) {
			// --- 步骤 1: 绘制焦点 (原始逻辑) ---
			super.afterAll();
			if (FOCUS_WINDOW instanceof ReviewElementWindow w && w.drawCell) {
				if (elem.parent instanceof Table t0) {
					CellView.drawFocusStatic(t0.getCell(elem), elem);
				}
			} else {
				TopGroup.drawFocus(elem, pos, focusColor, pos2 -> MyDraw.fontScaleDraw(() -> drawElemPad(elem, pos2)));
				MyDraw.fontScaleDraw(() -> drawGeneric(elem, pos));
			}

			if (!hoverInfoWindow.enabled()) return;

			// --- 步骤 2: 更新数据模型 (性能优化的核心) ---
			// 只有当焦点元素改变时，才执行昂贵的更新和布局操作
			if (elem != lastFocusedElement) {
				updateInfoPanel(elem);
				lastFocusedElement = elem;
			}

			// --- 步骤 3: 绘制UI (轻量级) ---
			// table.draw() 只是将已计算好的顶点数据发送给GPU，非常快。
			table.draw();
		}

		/**
		 * 新的辅助方法，封装了所有昂贵操作。
		 * 它只在焦点元素改变时被调用一次。
		 */
		private void updateInfoPanel(Element elem) {
			// 委托给 InfoDetails 更新所有条目
			info.updateFor(elem);

			// 在这里执行耗时的布局和定位操作
			table.invalidate();
			table.pack(); // pack() 包含了 layout 和 getPrefWidth 等操作
			table.act(0); // 确保所有动作执行
			info.setPosition(elem, table);
		}

		/** 绘制position和size */
		private void drawGeneric(Element elem, Vec2 pos) {
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
				if (pos.x != 0) {
					MyDraw.drawText(fixed(pos.x),
					 pos.x / 2f, pos.y, Tmp.c1.set(posTextColor));
				}
				// y: 0 -> y
				if (pos.y != 0) {
					MyDraw.drawText(fixed(pos.y),
					 pos.x, pos.y / 2f, Tmp.c1.set(posTextColor));
				}
			}
			posLine:
			{
				if (checkA(posLineColor)) break posLine;
				Lines.stroke(4);
				Draw.color(posLineColor);
				// x: 0 -> x
				if (pos.x != 0) Lines.line(0, pos.y, pos.x, pos.y);
				// y: 0 -> y
				if (pos.y != 0) Lines.line(pos.x, 0, pos.x, pos.y);
			}
			sizeText:
			{
				Color color = Tmp.c1.set(sizeTextColor);
				if (color.a == 0) break sizeText;
				float w = elem.getWidth();
				float h = elem.getHeight();
				// width
				boolean flipX = pos.x < 32, flipY = pos.y < 32;
				if (w != 0) {
					MyDraw.drawText(fixed(w),
					 pos.x + w / 2f,
					 (flipY ? Core.graphics.getHeight() - MyDraw.fontHeight() : MyDraw.fontHeight()),
					 color, Align.center);
				}

				// height
				if (h != 0) {
					MyDraw.drawText(fixed(h),
					 flipX ? Core.graphics.getWidth() : 0,
					 pos.y + (h + MyDraw.fontHeight()) / 2f,
					 color, flipX ? Align.right : Align.left);
				}
			}
		}

		/** 绘制padding和margin */
		private void drawElemPad(Element elem, Vec2 pos) {
			if (elem instanceof Table) {
				drawMargin(pos, (Table) elem);
			}

			if (elem.parent instanceof Table parent) {
				// 太难了
				drawMargin(elem.parentToLocalCoordinates(Tmp.v1.set(pos)), parent);

				drawPadding(elem, pos, parent);
			}
		}

		public boolean isSelecting() {
			return topGroup.elementCallback == callback;
		}
		public void endDraw() {
			if (isSelecting()) super.endDraw();
			drawLine();
			elem = topGroup.getSelected();
			if (elem != null) drawFocus(elem);
		}

	}

	// ========================================================================
	// 4. DetailEntry 的具体实现类
	//    这些类现在封装了所有的UI和逻辑。
	// ========================================================================
	//region Entry classes

	/** 辅助基类，用于处理通用的 "Key: Value" 行，并管理可见性 */
	private static abstract class BaseEntry implements DetailEntry, KeyValue {
		protected       BindCell       rowCell;
		protected       Boolf<Element> validator;
		protected final Boolf<Element> cellValidator = elem -> {
			boolean visible = CellTools.getCell(elem) != null;
			setVisible(visible);
			return visible;
		};
		public boolean valid(Element el) {
			return validator == null || validator.get(el);
		}
		protected void cellValidator() {
			this.validator = cellValidator;
		}

		protected void buildRow(Table table, String key, Element valueElement) {
			buildRow(table, key, t -> t.add(valueElement).growX().right());
		}

		@SuppressWarnings("SameParameterValue")
		protected void buildRow(Table table, String key, Cons<Table> valueBuilder) {
			rowCell = BindCell.ofConst(table.row().table(t -> {
				t.left().defaults().left();
				t.add(key).fontScale(keyScale).color(Color.lightGray).padRight(10f);
				valueBuilder.get(t);
			}).growX());
		}

		protected void setVisible(boolean visible) {
			if (rowCell != null) {
				rowCell.toggle(visible);
			}
		}
	}

	// --- 具体实现 ---
	private static class BoolEntry extends BaseEntry {
		private final Label          label = new VLabel("True", valueScale, Pal.accent);
		private final String         key;
		private final Boolf<Element> boolf;
		public BoolEntry(String key, Boolf<Element> boolf) {
			this.key = key;
			this.boolf = boolf;
		}
		public void build(Table table) {
			buildRow(table, key, label);
		}
		public void update(Element element) {
			setVisible(boolf.get(element));
		}
	}
	private static class HeaderEntry implements DetailEntry, KeyValue {
		private Label nameLabel, sizeLabel;
		private final Vec2     sizeVec  = new Vec2();
		private final SizeProv sizeProv = new SizeProv(() -> sizeVec, " × ");
		private       BindCell visibleCell;
		public boolean valid(Element element) {
			return true;
		}
		@Override
		public void build(Table table) {
			nameLabel = new VLabel(0.85f, stressColor);
			sizeLabel = new VLabel(valueScale, Color.lightGray);
			sizeLabel.setText(sizeProv);

			table.table(top -> {
				top.add(nameLabel).padLeft(-4f);
				visibleCell = BindCell.ofConst(top.image(Icon.eyeOffSmall).color(Pal.accent).size(16f).pad(4, 8, 4, 4));
				top.add(sizeLabel).padLeft(10f).right().labelAlign(Align.right).growX();
			}).growX();
			table.row();
		}

		@Override
		public void update(Element element) {
			nameLabel.setText(getElementName(element));
			sizeVec.set(element.getWidth(), element.getHeight());
			visibleCell.toggle(!element.visible);
		}
	}

	private static class SimpleEntry extends BaseEntry {
		private final String                key;
		private final Func<Element, String> textProvider;
		private final Func<Element, Color>  colorProvider;
		private       Label                 valueLabel;

		SimpleEntry(String key, Func<Element, String> textProvider, Func<Element, Color> colorProvider) {
			this.key = key;
			this.textProvider = textProvider;
			this.colorProvider = colorProvider;
		}

		@Override
		public void build(Table table) {
			valueLabel = new NoMarkupLabel(valueScale);
			buildRow(table, key, valueLabel);
		}

		@Override
		public void update(Element element) {
			valueLabel.setText(textProvider.get(element));
			if (colorProvider != null) {
				valueLabel.setColor(colorProvider.get(element));
			}
		}
	}

	private static class ColorEntry extends BaseEntry {
		private ColorContainer colorContainer;
		private Label          colorLabel;

		@Override
		public void build(Table table) {
			colorContainer = new ColorContainer(Color.white);
			colorLabel = new NoMarkupLabel(valueScale);
			buildRow(table, "Color", t -> {
				t.add(colorContainer).size(16).padRight(4f);
				t.add(colorLabel);
			});
		}

		@Override
		public void update(Element element) {
			Color color = element.color;
			colorContainer.setColorValue(color);
			String string = FormatHelper.color(color);
			colorLabel.setText(color.a == 1 ? string.substring(0, 6) : string);
		}
	}

	private static class ElemFieldEntry extends BaseEntry {
		private final String               fieldName;
		private final Field                field;
		private final Label                label;
		private final Cons2<Label, Object> labelCons;
		public ElemFieldEntry(Class<?> base, String fieldName, Label label, Cons2<Label, Object> labelCons) {
			this.fieldName = fieldName;
			field = FieldUtils.getFieldAccess(base, fieldName);
			if (field == null) throw new NullPointerException(fieldName);
			this.label = label != null ? label : new VLabel(valueScale, Color.lightGray);
			this.labelCons = labelCons;
		}
		public ElemFieldEntry(Class<?> base, String fieldName, Cons2<Label, Object> labelCons) {
			this(base, fieldName, null, labelCons);
		}
		public ElemFieldEntry(String fieldName) {
			this(Element.class, fieldName, null, (l, v) -> l.setText(FormatHelper.fixedAny(v)));
		}
		public void build(Table table) {
			buildRow(table, Strings.capitalize(fieldName), label);
		}
		public void update(Element element) {
			if (!field.getDeclaringClass().isInstance(element)) return;
			Object val = FieldUtils.get(element, field);
			labelCons.get(label, val);
			setVisible(switch (val) {
				case Number i -> i.intValue() != 0;
				// case Boolean b -> b;
				case String s -> !s.isEmpty();
				case Vec2 v -> v.x != 0 || v.y != 0;
				case null, default -> false;
			});
		}
	}
	private static class RotationEntry extends BaseEntry {
		private VLabel rotationLabel;
		@Override
		public void build(Table table) {
			rotationLabel = new VLabel(valueScale, Color.lightGray);
			buildRow(table, "Rotation", rotationLabel);
		}

		@Override
		public void update(Element element) {
			boolean visible = Mathf.equal(element.rotation % 360f, 0);
			setVisible(visible);
			if (visible) {
				rotationLabel.setText(fixed(element.rotation));
			}
		}
	}

	private static class ElemScaleEntry extends BaseEntry {
		private final Vec2 scaleVec = new Vec2();
		@Override
		public void build(Table table) {
			VLabel scaleLabel = new VLabel(valueScale, Color.white);
			scaleLabel.setText(new SizeProv(() -> scaleVec, " × "));
			buildRow(table, "ElemScale", scaleLabel);
		}
		@Override
		public void update(Element element) {
			scaleVec.set(element.scaleX, element.scaleY);
			boolean visible = !Mathf.equal(element.scaleX, 1) && Mathf.equal(element.scaleY, 1);
			setVisible(visible);
		}
	}
	private static class ScalingEntry extends BaseEntry {
		VLabel label = new VLabel("ImgScaling", valueScale, stressColor);
		public void build(Table table) {
			buildRow(table, "Scaling", label);
		}

		public void update(Element element) {
			boolean imgVisible = false;
			l:
			if (element instanceof Image image) {
				Scaling scaling = FieldUtils.get(image, FieldUtils.getFieldAccessOrThrow(Image.class, "scaling"));
				if (scaling == null) break l;
				label.setText(scaling.name());
				imgVisible = true;
			}
			setVisible(imgVisible);
		}
	}

	private static class StyleEntry extends BaseEntry {
		private Label styleLabel;
		@Override
		public void build(Table table) {
			styleLabel = new Label("");
			styleLabel.setFontScale(valueScale);
			buildRow(table, "Style", styleLabel);
		}

		@Override
		public void update(Element element) {
			try {
				Style style = (Style) element.getClass().getMethod("getStyle").invoke(element);
				if (style != null && ShowUIList.styleKeyMap.containsKey(style)) {
					setVisible(true);
					styleLabel.setText(FormatHelper.fieldFormat(ShowUIList.styleKeyMap.get(style)));
				} else {
					setVisible(false);
				}
			} catch (Throwable e) { setVisible(false); }
		}
	}

	private static class CellAlignEntry extends BaseEntry {
		private VLabel cellAlignLabel;

		@Override
		public void build(Table table) {
			cellAlignLabel = new VLabel(valueScale, Color.sky);
			buildRow(table, "CellAlign", cellAlignLabel);
		}

		@Override
		public void update(Element element) {
			Cell<?> cell = CellTools.getCell(element);
			setVisible(cell != null);
			if (cell != null) {
				cellAlignLabel.setText(FormatHelper.align(CellTools.align(cell)));
			}
		}
	}

	private static class ColspanEntry extends BaseEntry {
		private VLabel colspanLabel;

		@Override
		public void build(Table table) {
			colspanLabel = new VLabel(valueScale, Color.lightGray);
			buildRow(table, "Colspan", colspanLabel);
		}

		@Override
		public void update(Element element) {
			Cell<?> cell    = CellTools.getCell(element);
			int     colspan = (cell != null) ? CellTools.colspan(cell) : 1;
			boolean visible = colspan != 1;
			setVisible(visible);
			if (visible) {
				colspanLabel.setText(String.valueOf(colspan));
			}
		}
	}

	private static class LabelWrapEntry extends BaseEntry {
		private static final Field  wrapField = FieldUtils.getFieldAccess(Label.class, "wrap");
		private              VLabel wrapLabel;

		@Override
		public void build(Table table) {
			wrapLabel = new VLabel("", valueScale, Pal.accent);
			buildRow(table, "LabelWrap", wrapLabel);
		}

		@Override
		public void update(Element element) {
			if (element instanceof Label label) {
				setVisible(true);
				wrapLabel.setText((wrapField == null) ? "error" : String.valueOf(FieldUtils.getBoolean(label, wrapField)));
			} else {
				setVisible(false);
			}
		}
	}

	private static class SeparatorEntry implements DetailEntry {
		private final float top, left, bottom, right;
		public boolean valid(Element element) {
			return true;
		}
		public SeparatorEntry(float pad) { this(pad, pad, pad, pad); }
		public SeparatorEntry(float top, float left, float bottom, float right) {
			this.top = top;
			this.left = left;
			this.bottom = bottom;
			this.right = right;
		}

		BindCell bindCell;
		Table    table;
		@Override
		public void build(Table table) {
			bindCell = BindCell.ofConst(Underline.of(table, 2).pad(top, left, bottom, right));
			this.table = table;
		}

		@Override
		public void update(Element __) {
			if (bindCell == null) return;

			var cells        = table.getCells();
			int currentIndex = cells.indexOf(bindCell.cell, true); // true 表示使用 == 进行身份比较

			// 如果在父容器中找不到自己，则隐藏
			if (currentIndex == -1) {
				bindCell.remove();
				return;
			}

			// 条件A: 下一个元素存在
			Cell<?> nextCell       = ArrayUtils.find(cells, currentIndex, cell -> cell.get() != null);
			boolean hasNextElement = nextCell != null && nextCell.hasElement();

			// 条件B: 上一个元素不是 Underline
			boolean prevIsNotUnderline = true; // 默认为 true，因为如果没有上一个元素，也满足条件
			if (currentIndex > 0) { // 检查是否存在上一个元素
				var prev = ArrayUtils.findInverse(cells, currentIndex, cell -> cell.get() != null);
				// 如果上一个元素是 Underline 类型，则条件不满足
				if (prev != null && prev.get() instanceof Underline) {
					prevIsNotUnderline = false;
				}
			}

			// 综合两个条件
			boolean b = hasNextElement && prevIsNotUnderline;

			// 应用最终结果
			bindCell.toggle(b);
		}
	}
	/**
	 * 用于显示一个 Vec2 值的条目，例如 MinSize, MaxSize。
	 * 它使用 SizeProv 来格式化输出。
	 */
	private static class SizePairEntry extends BaseEntry {
		private final String                 key;
		private final Func<Cell<?>, Vec2>    valueProvider;
		private final Func<Cell<?>, Boolean> visibilityChecker;
		private final Vec2                   valueVec = new Vec2();

		/**
		 * @param key               显示的标签文本，如 "MinSize"
		 * @param valueProvider     一个函数，根据 Cell 返回 Vec2 值
		 * @param visibilityChecker 一个函数，判断此条目是否应该可见
		 */
		public SizePairEntry(String key, Func<Cell<?>, Vec2> valueProvider, Func<Cell<?>, Boolean> visibilityChecker) {
			this.key = key;
			this.valueProvider = valueProvider;
			this.visibilityChecker = visibilityChecker;
			cellValidator();
		}

		@Override
		public void build(Table table) {
			// SizeProv 会自动监听 valueVec 的变化并更新文本
			SizeProv sizeProv   = new SizeProv(() -> valueVec);
			Label    valueLabel = new Label(sizeProv);
			valueLabel.setFontScale(valueScale); // 假设 valueScale 是一个可访问的常量
			buildRow(table, key, valueLabel);
		}

		@Override
		public void update(Element element) {
			Cell<?> cell = CellTools.getCell(element);

			boolean visible = visibilityChecker.get(cell);
			setVisible(visible);

			if (visible) {
				// 从 provider 获取值并更新本地的 Vec2，
				// SizeProv 会自动处理标签的文本更新。
				Vec2 providedValue = valueProvider.get(cell);
				valueVec.set(providedValue.scl(1 / Scl.scl())); // 假设需要进行缩放
			}
		}
	}
	private enum ProviderType {
		element(Element.class), label(Label.class), scroll(ScrollPane.class),
		cell(Element.class) {
			public <E> E getValue(Element element) {
				return (E) CellTools.getCell(element);
			}
		};
		final Class<?> elementType;
		boolean valid(Element element) {
			return elementType.isInstance(element);
		}
		ProviderType(Class<?> elementType) {
			this.elementType = elementType;
		}
		public <E> E getValue(Element element) {
			return (E) element;
		}
	}
	private static class BoolPairEntry<E> extends BaseEntry {
		private final String       key;
		private final ProviderType providerType;

		private final Func<E, Vec2> valueProvider;
		private       Label         valueLabel;

		public BoolPairEntry(String key, ProviderType providerType, Func<E, Vec2> valueProvider) {
			this.key = key;
			this.providerType = providerType;
			this.valueProvider = valueProvider;
			cellValidator();
		}

		@Override
		public void build(Table table) {
			valueLabel = new Label(""); // 初始为空
			valueLabel.setFontScale(valueScale);
			buildRow(table, key, valueLabel);
		}

		@Override
		public void update(Element element) {
			if (!providerType.valid(element)) {
				setVisible(false);
				return;
			}
			Vec2    values = valueProvider.get(providerType.getValue(element));
			boolean x      = (values.x != 0);
			boolean y      = (values.y != 0);

			// 只有当至少一个为 true 时才显示
			boolean visible = x || y;
			setVisible(visible);

			if (visible) {
				// 使用原始代码中的格式化逻辑
				String text = String.format("%sx[] | %sy", enabledMark(x), enabledMark(y));
				valueLabel.setText(text);
			}
		}

		private String enabledMark(boolean enabled) {
			return enabled ? "[accent]" : "[gray]";
		}
	}
	private static class TranslationEntry extends BaseEntry {
		private final Vec2 translationVec = new Vec2();

		@Override
		public void build(Table table) {
			// PairProv 会自动监听 translationVec 的变化
			PairProv pairProv         = new PairProv(() -> translationVec, ", ");
			Label    translationLabel = new VLabel("", valueScale, Color.orange);
			translationLabel.setText(pairProv);
			buildRow(table, "Translation", translationLabel);
		}

		@Override
		public void update(Element element) {
			Vec2    translation = element.translation;
			boolean visible     = (translation.x != 0 || translation.y != 0);
			setVisible(visible);

			if (visible) {
				// 更新本地的 Vec2，PairProv 会自动更新 Label 的文本
				translationVec.set(translation);
			}
		}
	}
	/**
	 * 用于显示 Table, Label 的 align 属性。
	 */
	private static class AlignEntry<P> extends BaseEntry {
		private final String   key;
		private final Class<P> providerClass;
		private final Intf<P>  alignProvider;
		public AlignEntry(String key, Class<P> providerClass, Intf<P> alignProvider) {
			this.key = key;
			this.providerClass = providerClass;
			this.alignProvider = alignProvider;
		}

		private VLabel alignLabel;
		@Override
		public void build(Table table) {
			alignLabel = new VLabel(valueScale, Color.sky);
			buildRow(table, key, alignLabel);
		}

		@Override
		public void update(Element element) {
			if (providerClass.isInstance(element)) {
				setVisible(true);
				// 使用 FormatHelper 将整数 align 值转换为可读的字符串
				alignLabel.setText(FormatHelper.align(alignProvider.get((P) element)));
			} else {
				setVisible(false);
			}
		}
	}
	//endregion

	static Color DISABLED_COLOR = new Color(0xFF0000_FF);
	static Color touchableToColor(Touchable touchable) {
		return switch (touchable) {
			case enabled -> Color.green;
			case disabled -> DISABLED_COLOR;
			case childrenOnly -> Pal.accent;
		};
	}

	/** @see Align */
	public enum AlignR implements MarkedCode {
		Top(1),
		Bottom(0),
		Left(3),
		Right(2);

		public final int exclusive;
		AlignR(int exclusive) {
			this.exclusive = exclusive;
		}

		public int code() {
			return ordinal() + 1;
		}
		public MarkedCode exclusive() {
			return exclusive == -1 ? null : values()[exclusive];
		}
	}
	public static Cell<TextButton> buildAlign(Table t, Intp alignProv, Intc alignCons) {
		return ReflectTools.addCodedBtn(t, "Align", 2, alignCons, alignProv, FormatHelper::align, AlignR.values())
		 .width(unset);
	}

	/** 如果source元素有CellView接口，drawFocus按照下面来 */
	public interface CellView {
		default void drawFocus(Element focus) {
			if (focus == null || !(focus.parent instanceof Table table)) return;
			drawFocus(table.getCell(focus), focus);
		}
		default void drawFocus(Cell<?> cl, Element focus) {
			drawFocusStatic(cl, focus);
		}
		static void drawFocusStatic(Cell<?> cl, Element focus) {
			int   column = CellTools.column(cl);
			int   row    = CellTools.row(cl);
			Table table  = cl.getTable();
			float spanW  = table.getColumnWidth(column), spanH = table.getRowHeight(row);

			// cell的元素的坐标
			Vec2 pos = ElementUtils.getAbsolutePos(focus);

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
			float padTop = CellTools.computedPadTop(cl),
			 padBottom = CellTools.computedPadBottom(cl),
			 padLeft = CellTools.computedPadLeft(cl),
			 padRight = CellTools.computedPadRight(cl);

			// 渲染padding
			// drawMarginOrPad(pos, focus, true, padLeft, padTop, padRight, padBottom);

			// 左下角为 (0, 0)
			float spanX =
			 // left
			 (align & Align.left) != 0 ? -padLeft :
				// right
				(align & Align.right) != 0 ? padRight - spanW + focus.getWidth() :
				 // center
				 (focus.getWidth() - spanW - padLeft + padRight) / 2f;
			float spanY =
			 // top
			 (align & Align.top) != 0 ? padTop - spanH + focus.getHeight() :
				// bottom
				(align & Align.bottom) != 0 ? -padBottom :
				 // center
				 (focus.getHeight() - spanH - padBottom + padTop) / 2f;

			float x     = pos.x, y = pos.y;
			float thick = 2f;
			Lines.stroke(thick);
			Draw.color(Pal.remove);
			Drawf.dashRectBasic(x + spanX - thick / 2f, y + spanY - thick / 2f, spanW + thick, spanH + thick);

			thick = 3f;
			Lines.stroke(thick);
			Draw.color(Color.royal);
			Drawf.dashRectBasic(x - thick / 2f, y - thick / 2f, focus.getWidth() + thick, focus.getHeight() + thick);
		}
	}
}