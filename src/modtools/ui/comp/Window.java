package modtools.ui.comp;

import arc.Core;
import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.actions.*;
import arc.scene.event.*;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.ImageButton.ImageButtonStyle;
import arc.scene.ui.layout.*;
import arc.struct.ObjectSet;
import arc.util.*;
import arc.util.Timer.Task;
import mindustry.Vars;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.struct.MySet;
import modtools.ui.HopeStyles;
import modtools.ui.TopGroup.TSettings;
import modtools.ui.comp.buttons.FoldedImageButton;
import modtools.ui.comp.linstener.*;
import modtools.ui.effect.*;
import modtools.ui.effect.HopeFx.TranslateToAction;
import modtools.ui.gen.HopeIcons;
import modtools.ui.style.*;
import modtools.utils.*;
import modtools.utils.JSFunc.JColor;
import modtools.utils.ui.search.*;

import static arc.Core.graphics;
import static modtools.IntVars.mouseVec;
import static modtools.ui.Contents.window_manager;
import static modtools.ui.IntUI.*;
import static modtools.utils.Tools.as;

/**
 * <p>浮动的窗口，可以缩放，{@link #toggleMinimize() 最小化}，{@link #toggleMaximize() 最大化}</p>
 * <p>如果继承{@link IDisposable}，{@link #show()}，{@link #hide()}时自动销毁</p>
 * 记住左下角是{@code (0, 0)}
 * @author I hope...
 **/
public class Window extends Table implements Position {

	public static final MySet<Window> all = new MySet<>() {
		public boolean add(Window value) {
			boolean ok = super.add(value);
			if (window_manager != null && window_manager.ui != null && window_manager.ui.isShown())
				window_manager.rebuild();
			return ok;
		}

		public boolean remove(Window value) {
			boolean ok = super.remove(value);
			if (window_manager != null && window_manager.ui != null && window_manager.ui.isShown())
				window_manager.rebuild();
			return ok;
		}
	};

	public static boolean disabledActions = false;

	/** 最前面的窗口 */
	public static Window frontWindow;

	static {
		IntVars.addResizeListener(() -> all.each(Window::display));
		Tools.TASKS.add(() -> frontWindow = ArrayUtils.getBound(topGroup.acquireShownWindows(), -1));
	}

	public static final DelegatingDrawable myPane = (DelegatingDrawable) TSettings.paneDrawable.getDrawable(Tex.pane);

	public static Drawable topPane
	 = new TintDrawable(whiteui, () -> JColor.c_window_title);

	public static ImageButtonStyle cancel_clearNonei = new ImageButtonStyle(HopeStyles.hope_clearNonei) {{
		over = whiteui.tint(Pal.remove);
		down = whiteui.tint(Tmp.c1.set(Pal.remove).lerp(Color.gray, 0.3f));
	}};

	public static final  float buttonSize = 38f;
	// 用于最小化时的最小宽度
	private static final float topHeight  = 45;

	public Table
	 titleTable = new Table(topPane) {
		public Cell<ImageButton> button(Drawable icon, ImageButtonStyle style, float isize, Runnable listener) {
			var cell = super.button(icon, style, isize, listener);
			cell.get().tapped(() -> {
				if (moveListener == null) return;
				moveListener.disabled = true;
				Core.app.post(() -> moveListener.disabled = false);
			});
			return cell;
		}
	},
	/** container */
	cont     = new Table(myPane),
	 buttons = new Table(myPane);
	public float minWidth, minHeight;


	public Label title;

	/** 是否为一个完整的Window */
	public final boolean full;

	/** 是否置顶 */
	public boolean
	 sticky = false,
	/** 是否没有buttons */
	noButtons;
	private MoveListener moveListener;
	public MoveListener moveListener() {
		return new ReferringMoveListener(titleTable, this,
		 new float[]{0, 0.5f, 1},
		 new float[]{0, 0.5f, 1}) {
			public void display(float x, float y) {
				Vec2 pos = snap(x, y);
				Window.this.display(pos.x, pos.y);
			}
		};
	}
	// public ObjectSet<Element> fireMoveElems = ObjectSet.with(this, titleTable);
	public SclListener sclListener;

	public Window(String title, float minWidth, float minHeight, boolean full, boolean noButtons) {
		super();

		cont.setClip(true);
		tapped(this::toFront);
		touchable = titleTable.touchable/* = cont.touchable */ = Touchable.enabled;
		titleTable.margin(0);
		if ((IntVars.isDesktop()) && full) EventHelper.doubleClick(titleTable, null, this::toggleMaximize);
		cont.margin(6f);
		buttons.margin(0);
		this.minWidth = minWidth * Scl.scl();
		this.minHeight = minHeight * Scl.scl();
		this.full = full;
		this.noButtons = noButtons;

		left().defaults().left();

		buildTitle(title, full);

		sclListener = new SclListener(this, this.minWidth, this.minHeight);
		if (moveListener != null) moveListener.fire = () -> {
			if (isMaximize && !isMinimize) {
				float mulxw = moveListener.lastMouse.x / width;
				float mulxh = moveListener.lastMouse.y / height;
				toggleMaximize();
				// 修复移动侦听器的位置
				RunListener listener = new RunListener() {
					public void fire(boolean status) {
						moveListener.lastMain.x = x;
						moveListener.lastMain.y = y;
						moveListener.lastMouse.x = x + width * mulxw;
						moveListener.lastMouse.y = y + height * mulxh;
						maxlisteners.remove(this);
					}
				};
				maximized(listener);
			}
		};

		Core.app.post(() -> {
			// 默认宽度为pref宽度
			this.minWidth = getMinWidth();
			this.minHeight = getMinHeight();
			sclListener.set(this.minWidth, this.minHeight);
			if (this instanceof IDisposable) show();
		});
		all.add(this);

		IntVars.addResizeListener(() -> {
			if (isMaximize) maximizeAnimated();
		});
	}
	public float getMinWidth() {
		return Math.max(minWidth, super.getMinWidth());
	}
	public float getMinHeight() {
		return Math.max(minHeight, super.getMinHeight());
	}

	private void buildTitle(String title, boolean full) {
		moveListener = moveListener();
		add(titleTable).growX().height(topHeight).name("titleTable");
		row();

		this.title = titleTable.add(title)
		 .grow().touchable(Touchable.disabled)
		 .padLeft(6f).padRight(6f)
		 .update(l -> l.setColor(frontWindow == this ? Color.white : Color.lightGray))
		 .get();

		titleTable.defaults().size(buttonSize);


		if (full) {
			titleTable.button(HopeIcons.sticky, HopeStyles.hope_clearNoneTogglei, 32,
				() -> sticky = !sticky).padLeft(4f).name("sticky")
			 .checked(_ -> sticky);
			titleTable.add(new FoldedImageButton(false, HopeStyles.hope_clearNonei))
			 .with(b -> {
				 b.resizeImage(32);
				 b.clicked(this::toggleMinimize);
			 }).padLeft(4f);
			ImageButton button = titleTable.button(Tex.whiteui, HopeStyles.hope_clearNonei, 28, this::toggleMaximize).disabled(b -> !isShown()).padLeft(4f).get();
			button.update(() -> {
				button.getStyle().imageUp = isMaximize ? HopeIcons.normal : HopeIcons.maximize;
			});
		}
		FillTable resize = getResizeFillTable();

		EventHelper.longPressOrRclick(
		 titleTable.button(Icon.cancel,
			 cancel_clearNonei, 32, this::hide)
			.padLeft(4f).padRight(4f)
			.get(),
		 _ -> resize.show());

		setup();
	}


	public void layout() {
		super.layout();
		display();
	}
	public void act(float delta) {
		Tools.runIgnoredException(() -> super.act(delta));
		if (sticky) toFront();
		sclListener.disabled0 = isMaximize;
		if (moveListener != null) moveListener.disabled = sclListener.scling;
	}
	private FillTable getResizeFillTable() {
		return addFillTable(p -> {
			ImageButtonStyle style = HopeStyles.flati;
			final float      size  = 28;
			p.shown = () -> sclListener.offset = size;
			p.button(Icon.leftOpenSmall, style, IntVars.EMPTY_RUN).size(size).get().getImage().rotation = -45;
			p.button(Icon.upOpenSmall, style, IntVars.EMPTY_RUN).growX().height(size);
			p.button(Icon.rightOpenSmall, style, IntVars.EMPTY_RUN).size(size).get().getImage().rotation = 45;

			p.row();
			p.button(Icon.leftOpenSmall, style, IntVars.EMPTY_RUN).growY().width(size);
			p.table(t -> t.button(Icon.cancelSmall, style, () -> {
				sclListener.offset = SclListener.defOffset;
				p.hide();
			}).size(32)).grow();
			p.button(Icon.rightOpenSmall, style, IntVars.EMPTY_RUN).growY().width(size);

			p.row();
			p.button(Icon.leftOpenSmall, style, IntVars.EMPTY_RUN).size(size).get().getImage().rotation = 45;
			p.button(Icon.downOpenSmall, style, IntVars.EMPTY_RUN).growX().height(size);
			p.button(Icon.rightOpenSmall, style, IntVars.EMPTY_RUN).size(size).get().getImage().rotation = -45;
		});
	}

	public Window(String title, float minWidth, float minHeight, boolean full) {
		this(title, minWidth, minHeight, full, true);
	}
	public Window(String title, float minWidth, float minHeight) {
		this(title, minWidth, minHeight, false);
	}
	public Window(String title) {
		this(title, 120, 80, false);
	}


	/** 自动解除focus */
	public Element hit(float x, float y, boolean touchable) {
		Element hit = super.hit(x, y, touchable);
		if (hit == null && this instanceof IMenu) hit = Hitter.firstTouchable();
		if (hit == null) {
			if (Core.scene.getScrollFocus() != null && Core.scene.getScrollFocus().isDescendantOf(this))
				Core.scene.setScrollFocus(null);
			if (Core.scene.getKeyboardFocus() != null && Core.scene.getKeyboardFocus().isDescendantOf(this))
				Core.scene.setKeyboardFocus(null);
		}
		return hit;
	}
	private void setup() {
		add(cont).name("cont").grow().row();
		if (!noButtons) add(buttons).name("buttons").growX().row();
	}

	public void display() {
		display(x, y);
	}
	/** 超出屏幕外的距离 */
	float overMultiple = 0.33f;
	private void display(float x, float y) {
		float mainWidth  = getWidth(), mainHeight = getHeight();
		float touchWidth = titleTable.getWidth(), touchHeight = titleTable.getHeight();

		float offset = Scl.scl(45 * 4);
		float minX   = (this instanceof PopupWindow ? 0 : Math.min(-touchWidth * overMultiple, -mainWidth + offset));
		float maxX = (this instanceof PopupWindow ? 0 : Math.max(-mainWidth + touchWidth * overMultiple, -offset))
		             + graphics.getWidth();
		float minY = (this instanceof PopupWindow ? 0 : -mainHeight + touchHeight * overMultiple * 2f);
		float maxY = -mainHeight + graphics.getHeight();

		super.setPosition(Mathf.clamp(x, minX, maxX),
		 Mathf.clamp(y, minY, maxY));
		/* if (lastMaximize) {
			// false取反为true
			isMaximize = false;
			toggleMaximize();
		} */
	}

	public static boolean mobileDisabled = false;
	/** 截图 */
	public TextureRegion screenshot() {
		if (Vars.mobile && mobileDisabled) return null;
		return cache = isMinimize || cont == null ? cache : ElementUtils.screenshot(cont, true, null);
	}


	public float getPrefWidth() {
		return Mathf.clamp(super.getPrefWidth(), minWidth, graphics.getWidth());
	}
	public float getPrefHeight() {
		return Mathf.clamp(super.getPrefHeight(), minHeight, graphics.getHeight());
	}

	private static final Prov<Action>
	 defaultShowAction = () -> Actions.sequence(Actions.alpha(0), Actions.fadeIn(0.2f, Interp.fade)),
	 defaultHideAction = () -> Actions.fadeOut(0.2f, Interp.fade);

	protected InputListener ignoreTouchDown = new InputListener() {
		public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
			event.cancel();
			return false;
		}
	};

	public void clear() {
		super.clear();
		all.remove(this);
	}

	public boolean isShown() {
		return getScene() != null;
	}


	/**
	 * {@link #pack() Packs} the dialog and adds it to the stage with custom action which can be null for instant show
	 */
	public Window show(Scene stage, Action action) {
		Core.app.post(() -> show0(stage, action));
		return this;
	}

	Hitter hitter;
	void show0(Scene stage, Action action) {
		if (this instanceof IHitter) {
			topGroup.addChild(hitter = new Hitter(this::hide));
		}
		setOrigin(Align.center);
		setClip(false);

		this.fire(new VisibilityEvent(false));

		clearActions();
		removeCaptureListener(ignoreTouchDown);

		topGroup.addChild(this);

		if (action != null) addAction(action);
		pack();

		if (!Window.all.contains(this)) {
			Window.all.add(this);
		}

		// if (!(this instanceof InfoFadePopup)) Core.scene.unfocusAll();
		// stage.setKeyboardFocus(this);
	}
	public void pack() {
		if (isMinimize) return;
		if (isMaximize) {
			setSize(graphics.getWidth(), graphics.getHeight());
		} else {
			super.pack();
		}
	}
	/**
	 * Shows this dialog if it was hidden, and vice versa.
	 */
	public void toggle() {
		if (isShown()) {
			hide();
		} else {
			show();
		}
	}

	public Window show() {
		/* 以免window超出屏幕外  */
		Time.runTask(4, this::display);

		if (isShown()) {
			setZIndex(Integer.MAX_VALUE);
			if (isMinimize) toggleMinimize();
			return this;
		}
		return show(Core.scene);
	}

	/**
	 * {@link #pack() Packs} the dialog and adds it to the stage, centered with default fadeIn action
	 */
	public Window show(Scene stage) {
		show(stage, defaultShowAction.get());
		return this;
	}

	/**
	 * Hides the dialog with the given action and then removes it from the stage.
	 */
	public void hide(Action action) {
		if (hitter != null) {
			hitter.remove();
			hitter = null;
		}
		unexpectedDrawException = false;
		// bakRegion = screenshot();
		this.fire(new VisibilityEvent(true));

		SequenceAction sq;
		if (action != null) {
			addCaptureListener(ignoreTouchDown);
			sq = Actions.sequence(action, Actions.removeListener(ignoreTouchDown, true));
		} else {
			sq = Actions.sequence();
		}
		if (this instanceof IDisposable dis) {
			sq.addAction(Actions.run(() -> {
				all.remove(this);
				dis.clearAll();
			}));
		}
		sq.addAction(Actions.remove());
		addAction(sq);
	}

	public TextureRegion cache = null;
	/**
	 * Hides the dialog. Called automatically when a button is clicked. The default implementation fades out the dialog over 400
	 * milliseconds.
	 */
	public void hide() {
		if (!isShown()) return;
		if (!(this instanceof IDisposable)) screenshot();
		setOrigin(Align.center);
		setClip(false);

		hide(defaultHideAction.get());
	}

	// 用于存储最小/大化前的位置和大小
	public Rect    lastRect        = new Rect();

	public ObjectSet<RunListener> maxlisteners = new ObjectSet<>();
	public boolean                isMaximize   = false, lastMaximize = false;

	/**
	 * Adds a toggleMaximize() listener.
	 */
	public void maximized(RunListener run) {
		maxlisteners.add(run);
	}

	public void toggleMaximize() {
		boolean lastMin = isMinimize;
		if (isMinimize) toggleMinimize();
		isMaximize = !isMaximize;
		if (isMaximize) {
			if (!lastMin) lastRect.set(x, y, width, height);

			maximizeAnimated();
		} else {
			moveAndScaleAnimated(lastRect.x, lastRect.y, lastRect.width, lastRect.height);
		}
		act(0);

		loopTask(false);
	}
	private void loopTask(boolean isMin) {
		Timer.schedule(new MyTask(isMin), 0, 0.01f, -1);
	}
	private void maximizeAnimated() {
		moveAndScaleAnimated(0, 0, graphics.getWidth(), graphics.getHeight());
	}
	private void moveAndScaleAnimated(float x, float y, float width, float height) {
		addAction(Actions.parallel(
		 Actions.sizeTo(width, height, disabledActions ? 0 : 0.06f),
		 Actions.moveTo(x, y, disabledActions ? 0 : 0.06f)
		));
	}


	public ObjectSet<RunListener> minlisteners = new ObjectSet<>();
	public boolean                isMinimize   = false;

	public void toggleMinimize() {
		if (!isMinimize) screenshot();
		isMinimize = !isMinimize;
		if (isMinimize) {
			if (!isMaximize) {
				lastRect.setSize(getWidth(), getHeight());
			}

			moveAndScaleAnimated(x, y + lastRect.height - topHeight,
			 getMinWidth(), topHeight);

			getCell(cont).set(BindCell.UNSET_CELL);
			cont.remove();
			if (!noButtons) Tools.runIgnoredException(() -> {
				getCell(buttons).set(BindCell.UNSET_CELL);
				buttons.remove();
			});

			sclListener.remove();
			sizeChanged();
		} else {
			setup();

			left().top();
			if (isMaximize) {
				maximizeAnimated();
			} else {
				moveAndScaleAnimated(lastRect.x = x,
				 lastRect.y = y - lastRect.height + topHeight,
				 lastRect.width, lastRect.height);
			}
			sclListener.bind();
		}
		act(0);

		loopTask(true);
	}

	/**
	 * Adds a toggleMinimize() listener.
	 */
	public void minimized(RunListener run) {
		minlisteners.add(run);
	}

	/**
	 * Adds a show() listener.
	 */
	public void shown(Runnable run) {
		addListener(new VisibilityListener() {
			@Override
			public boolean shown() {
				run.run();
				return false;
			}
		});
	}

	/**
	 * Adds a hide() listener.
	 */
	public void hidden(Runnable run) {
		addListener(new VisibilityListener() {
			@Override
			public boolean hidden() {
				run.run();
				return false;
			}
		});
	}

	public Window setPosition(Position pos) {
		super.setPosition(pos.getX(), pos.getY());
		display();
		return this;
	}
	public Window setPosition(Position pos, int align) {
		super.setPosition(pos.getX(), pos.getY(), align);
		display();
		return this;
	}

	public boolean fire(SceneEvent event) {
		boolean[] b = {false};
		Tools.runLoggedException(() -> b[0] = super.fire(event));
		return b[0];
	}

	boolean unexpectedDrawException;
	Window  confirm;
	final Mat oldTransform = new Mat();
	public void draw() {
		topGroup.drawResidentTasks.forEach(task -> task.beforeDraw(this));
		float prev = Draw.z();
		Draw.draw(getZIndex() + 11, () -> {
			Draw.alpha(parentAlpha);
			MyDraw.blurRect(x, y, width, height);
		});
		Draw.z(prev);
		super.draw();
	}

	protected void drawChildren() {
		if (unexpectedDrawException) return;

		oldTransform.set(Draw.trans());
		Tools.runLoggedException(super::drawChildren, () -> {
			/* draw错误捕获 */
			unexpectedDrawException = true;
			children.end();

			Tools.runIgnoredException(() -> {
				/* 尽可能的退出clip */
				for (int i = 0; i < 100; i++) clipEnd();
			});
			Draw.trans(oldTransform);
			confirm = showCustomConfirm("@settings.exception", "@settings.exception.draw",
			 "@settings.window.close", "@settings.window.keep",
			 this::remove, () -> unexpectedDrawException = false);
			confirm.sclListener.remove();
			Boolp boolp = () -> confirm.isShown();
			topGroup.disabledSwitchPreviewSeq.add(boolp);
			confirm.update(() -> confirm.setPosition(this));
			confirm.hidden(() -> {
				topGroup.disabledSwitchPreviewSeq.remove(boolp, true);
				confirm = null;
			});
		});
	}

	/**
	 * 添加一个充满整个窗口的Table
	 * @param cons 跟Table一样
	 * @see Table
	 */
	public FillTable addFillTable(Cons<FillTable> cons) {
		return new FillTable(Styles.black5, cons);
	}

	public Window moveToMouse() {
		return setPosition(mouseVec);
	}
	public float getX() {
		return x;
	}
	public float getY() {
		return y;
	}

	public class FillTable extends Table {
		{
			fillParent = true;
			touchable = Touchable.enabled;
		}

		public Element hit(float x, float y, boolean touchable) {
			return SR.of(super.hit(x, y, touchable))
			 .set(e -> e == this, titleTable)
			 .get();
		}
		public FillTable() { }
		public FillTable(Drawable background) {
			super(background);
		}
		public FillTable(Drawable background, Cons<FillTable> cons) {
			super(background, as(cons));
		}
		public FillTable(Cons<FillTable> cons) {
			super((Cons) cons);
		}
		public void show() {
			if (shown != null) shown.run();
			Window.this.addChild(this);
			setPosition(0, 0);
		}
		public void hide() {
			remove();
		}
		public Runnable shown;
	}

	public interface RunListener {
		void fire(boolean status);
	}

	/** 窗口会自动销毁
	 * 而且新建就{@link #show()} */
	public interface IDisposable {
		default void clearAll() {
			if (!(this instanceof Group g)) return;
			g.find(el -> {
				if (el instanceof FilterTable<?>) {
					Core.app.post(el::clear);
				}

				el.update(null);
				el.userObject = null;
				return false;
			});
			g.clearChildren();
			g.clearListeners();
		}
	}

	/**
	 * 延迟几秒销毁的窗口
	 * @see InfoFadePopup
	 */
	public interface DelayDisposable extends IDisposable { }

	public static class DisWindow extends Window implements IDisposable {
		public DisWindow(String title, float minWidth, float minHeight, boolean full) {
			super(title, minWidth, minHeight, full);
		}
		public DisWindow(String title, float minWidth, float minHeight) {
			super(title, minWidth, minHeight);
		}
		public DisWindow(String title) {
			super(title);
		}
	}

	public String toString() {
		return ElementUtils.getElementName(this);
	}
	public static class NoTopWindow extends Window {
		public NoTopWindow(String title, float width, float height, boolean full) {
			super(title, width, height, full);
		}
		public NoTopWindow(String title, float minWidth, float minHeight, boolean full, boolean noButtons) {
			super(title, minWidth, minHeight, full, noButtons);
		}
		public NoTopWindow(String title, float width, float height) {
			super(title, width, height);
		}
		public NoTopWindow(String title) {
			super(title);
		}
		public void display() {
			keepInStage();
		}
		public MoveListener moveListener() {
			return null;
		}
		{
			getCells().remove(getCell(titleTable).clearElement(), true);
		}
	}

	public static class HiddenTopWindow extends Window {

		public HiddenTopWindow(String title, float minWidth, float minHeight, boolean full, boolean noButtons) {
			super(title, minWidth, minHeight, full, noButtons);
		}
		public HiddenTopWindow(String title, float width, float height, boolean full) {
			super(title, width, height, full);
		}
		public HiddenTopWindow(String title, float width, float height) {
			super(title, width, height);
		}
		public HiddenTopWindow(String title) {
			super(title);
		}

		public float titleHeight = topHeight;

		{
			// 从window中删除titleTable
			getCells().remove(getCell(titleTable).clearElement(), true);
			// 新建一个container
			Table container = new Table();
			container.setFillParent(true);
			container.top().add(titleTable).growX().height(titleHeight);
			titleTable.translation.y = titleHeight;
			container.setClip(true);
			addChild(container);

			titleTable.invalidateHierarchy();
			class ExitListener extends InputListener {
				public void exit(InputEvent event, float x, float y, int pointer, Element toActor) {
					/* 如果toActor是titleTable的子节点，就忽略 */
					if (toActor == null || toActor.isDescendantOf(titleTable)) return;
					translateTo(topHeight);
				}
			}
			titleTable.addListener(new ExitListener());
			/*addListener(new InputListener() {
				public void exit(InputEvent event, float x, float y, int pointer, Element toActor) {
					top.translation.y = 0;
				}
			});*/
		}

		TranslateToAction last;
		public Element hit(float x, float y, boolean touchable) {
			Element element = super.hit(x, y, touchable);
			translateTo(height - titleHeight <= y && element != null && !isMinimize ? 0 : titleHeight);
			return element;
		}
		private void translateTo(float toValue) {
			if (last == null || toValue != last.getY()) applyAction(toValue);
		}
		private void applyAction(float toValue) {
			if (last != null) {
				titleTable.removeAction(last);
			} else last = Actions.action(TranslateToAction.class, HopeFx.TranslateToAction::new);
			last.reset();
			last.setTime(0);
			last.setTranslation(0, toValue);
			last.setDuration(0.3f);
			last.setInterpolation(Interp.fastSlow);
			titleTable.addAction(last);
		}
	}
	private class MyTask extends Task {
		boolean isMin;
		public MyTask(boolean isMin) {
			this.isMin = isMin;
		}
		public void run() {
			if (!hasActions()) {
				if (isMin && !isMaximize) display();

				if (!isMin) lastMaximize = isMaximize;
				(isMin ? minlisteners : maxlisteners)
				 .each(r -> r.fire((isMin ? isMinimize : isMaximize)));

				display();

				cancel();
			}
		}
	}
}