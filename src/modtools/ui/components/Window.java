package modtools.ui.components;

import arc.Core;
import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.actions.Actions;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.ImageButton.ImageButtonStyle;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import arc.util.Timer.Task;
import mindustry.Vars;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.ui.*;
import modtools.ui.Frag.ClearScroll;
import modtools.ui.HopeIcons;
import modtools.ui.components.buttons.FoldedImageButton;
import modtools.ui.components.linstener.*;
import modtools.ui.effect.*;
import modtools.ui.effect.HopeFx.TranslateToAction;
import modtools.ui.IntUI;
import modtools.ui.style.TintDrawable;
import modtools.utils.*;
import modtools.struct.MySet;
import modtools.utils.JSFunc.JColor;
import modtools.utils.ArrayUtils;
import modtools.utils.ui.search.*;


import static arc.Core.graphics;
import static modtools.ui.Contents.window_manager;
import static modtools.ui.IntUI.*;
import static modtools.utils.Tools.*;

/**
 * <p>浮动的窗口，可以缩放，{@link #toggleMinimize() 最小化}，{@link #toggleMaximize() 最大化}</p>
 * <p>如果继承{@link IDisposable}，{@link #show()}，{@link #hide()}时自动销毁</p>
 * 记住左下角是{@code (0, 0)}
 * @author I hope...
 **/
public class Window extends Table {
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

	public static Window frontWindow;

	static {
		IntVars.addResizeListener(() -> all.each(Window::display));
		Tools.TASKS.add(() -> frontWindow = ArrayUtils.getBound(topGroup.acquireShownWindows(), -1));
	}

	public static Drawable myPane  = Tex.pane;
	public static Drawable topPane = new TintDrawable(IntUI.whiteui, () -> JColor.c_window_title);

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
				moveListener.disabled = true;
				Time.runTask(0, () -> moveListener.disabled = false);
			});
			return cell;
		}
	},
	/** container */
	cont     = new Table(myPane),
	 buttons = new Table(myPane);
	public float minWidth, minHeight;


	public Label title;

	/** 是否为一个完整的Window  */
	public final boolean full;

	/** 是否置顶 */
	public boolean
	 sticky = false,
	/** 是否没有buttons */
	noButtons;
	public MoveListener moveListener;
	// public ObjectSet<Element> fireMoveElems = ObjectSet.with(this, titleTable);
	public SclListener  sclListener;

	public Window(String title, float minWidth, float minHeight, boolean full, boolean noButtons) {
		super();

		cont.setClip(true);
		tapped(this::toFront);
		touchable = titleTable.touchable/* = cont.touchable */ = Touchable.enabled;
		titleTable.margin(0);
		if ((OS.isWindows || OS.isMac) && full) IntUI.doubleClick(titleTable, null, this::toggleMaximize);
		cont.margin(6f);
		buttons.margin(0);
		this.minHeight = minHeight;
		this.full = full;
		this.noButtons = noButtons;

		left().defaults().left();

		buildTitle(title, full);

		sclListener = new SclListener(this, this.minWidth, minHeight);
		moveListener.fire = () -> {
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
			// 默认最小宽度为pref宽度
			this.minWidth = Math.max(minWidth, getMinWidth());
			float minHeight0 = Math.max(minHeight, getMinHeight());
			sclListener.set(this.minWidth, minHeight0);
			setSize(Math.max(this.minWidth, getWidth()),
			 Math.max(this.minHeight, getHeight()));
			if (this instanceof IDisposable) show();
		});
		all.add(this);

		addListener(new ClearScroll());
	}
	private void buildTitle(String title, boolean full) {
		add(titleTable).growX().height(topHeight).name("titleTable");
		row();
		moveListener = new MoveListener(titleTable, this) {
			public void display(float x, float y) {
				Window.this.display(x, y);
			}
		};

		this.title = titleTable.add(title)
		 .grow().touchable(Touchable.disabled)
		 .padLeft(6f).padRight(6f)
		 .update(l -> {
			 l.setColor(frontWindow == this ? Color.white : Color.lightGray);
		 })
		 .get();

		titleTable.defaults().size(buttonSize);

		if (full) {
			//noinspection rawtypes
			titleTable.button(HopeIcons.sticky, HopeStyles.hope_clearNoneTogglei, 32, () -> {
				 sticky = !sticky;
			 }).padLeft(4f).name("sticky")
			 /* 这是一个奇葩的bug */
			 .checked((Boolf) t -> sticky);
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

		IntUI.longPressOrRclick(
		 titleTable.button(Icon.cancel,
			 cancel_clearNonei, 32, this::hide)
			.padLeft(4f).padRight(4f)
			.get(), __ -> resize.show());
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
		moveListener.disabled = sclListener.scling;
	}
	private FillTable getResizeFillTable() {
		return addFillTable(p -> {
			ImageButtonStyle style = HopeStyles.flati;
			final float      size  = 28;
			p.shown = () -> sclListener.offset = size;
			p.button(Icon.leftOpenSmall, style, () -> {}).size(size).get().getImage().rotation = -45;
			p.button(Icon.upOpenSmall, style, () -> {}).growX().height(size);
			p.button(Icon.rightOpenSmall, style, () -> {}).size(size).get().getImage().rotation = 45;

			p.row();
			p.button(Icon.leftOpenSmall, style, () -> {}).growY().width(size);
			p.table(t -> {
				t.button(Icon.cancelSmall, style, () -> {
					sclListener.offset = SclListener.defOffset;
					p.hide();
				}).size(32);
			}).grow();
			p.button(Icon.rightOpenSmall, style, () -> {}).growY().width(size);

			p.row();
			p.button(Icon.leftOpenSmall, style, () -> {}).size(size).get().getImage().rotation = 45;
			p.button(Icon.downOpenSmall, style, () -> {}).growX().height(size);
			p.button(Icon.rightOpenSmall, style, () -> {}).size(size).get().getImage().rotation = -45;
		});
	}

	public Window(String title, float width, float height, boolean full) {
		this(title, width, height, full, true);
	}
	public Window(String title, float width, float height) {
		this(title, width, height, false);
	}
	public Window(String title) {
		this(title, 120, 80, false);
	}


	/** 如果hit元素不是想要的，取消MoveListener事件 */
	public Element hit(float x, float y, boolean touchable) {
		// if (!moveListener.isFiring) moveListener.disabled = element == null || !fireMoveElems.contains(element);
		Element hit = super.hit(x, y, touchable);
		if (hit == null && this instanceof IMenu) hit = Hitter.all.first();
		return hit;
	}
	private void setup() {
		add(cont).name("cont").grow().row();
		if (!noButtons) add(buttons).name("buttons").growX().row();
	}

	public void display() {
		display(x, y);
	}
	private void display(float x, float y) {
		float mainWidth  = getWidth(), mainHeight = getHeight();
		float touchWidth = titleTable.getWidth(), touchHeight = titleTable.getHeight();

		float offset = Scl.scl(45 * 4);
		float minX   = (this instanceof PopupWindow ? 0 : Math.min(-touchWidth / 3f, -mainWidth + offset));
		float maxX = (this instanceof PopupWindow ? 0 : Math.max(-mainWidth + touchWidth / 2f, -offset))
								 + graphics.getWidth();
		float minY = (this instanceof PopupWindow ? 0 : -mainHeight + touchHeight / 3f * 2f);
		float maxY = -mainHeight + graphics.getHeight();
		super.setPosition(Mathf.clamp(x, minX, maxX),
		 Mathf.clamp(y, minY, maxY));
		/* if (lastMaximize) {
			// false取反为true
			isMaximize = false;
			toggleMaximize();
		} */
	}


	public static boolean Modible_Disabled = false;
	/** 截图 */
	public TextureRegion screenshot() {
		// return getFrameBufferTexture((int) x, (int) y, (int) width, (int) height);
		// return new TextureRegion(bufferCapture(this));
		if (Vars.mobile && Modible_Disabled) return null;
		return cache = isMinimize || cont == null ? cache : ElementUtils.screenshot(cont, true, null);
	}


	public float getPrefWidth() {
		// 默认最小宽度为顶部的最小宽度
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
	Element previousKeyboardFocus, previousScrollFocus;
	FocusListener focusListener = new FocusListener() {
		public void keyboardFocusChanged(FocusEvent event, Element actor, boolean focused) {
			if (!focused) focusChanged(event);
		}

		public void scrollFocusChanged(FocusEvent event, Element actor, boolean focused) {
			if (!focused) focusChanged(event);
		}

		private void focusChanged(FocusEvent event) {
			Scene stage = getScene();
			if (stage != null && stage.root.getChildren().size > 0
					&& stage.root.getChildren().peek() == Window.this) { // Dialog is top most actor.
				Element newFocusedActor = event.relatedActor;
				if (newFocusedActor != null && !newFocusedActor.isDescendantOf(Window.this) &&
						!(newFocusedActor.equals(previousKeyboardFocus) || newFocusedActor.equals(previousScrollFocus)))
					event.cancel();
			}
		}
	};

	public void clear() {
		super.clear();
		all.remove(this);
	}

	@Override
	protected void setScene(Scene stage) {
		if (stage == null) {
			addListener(focusListener);
		} else {
			removeListener(focusListener);
		}
		super.setScene(stage);
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

		previousKeyboardFocus = null;
		Element actor = stage.getKeyboardFocus();
		if (actor != null && !actor.isDescendantOf(this)) previousKeyboardFocus = actor;

		previousScrollFocus = null;
		actor = stage.getScrollFocus();
		if (actor != null && !actor.isDescendantOf(this)) previousScrollFocus = actor;

		pack();
		topGroup.addChild(this);

		if (action != null) addAction(action);
		pack();

		if (!Window.all.contains(this)) {
			Window.all.add(this);
		}

		if (!(this instanceof InfoFadePopup)) Core.scene.unfocusAll();
		// stage.setKeyboardFocus(this);
		invalidate();

	}
	public void pack() {
		if (isMinimize) return;
		if (!isMaximize) {
			super.pack();
			return;
		}
		setSize(graphics.getWidth(), graphics.getHeight());
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
		Time.runTask(4, () -> {
			invalidateHierarchy();
			display();
		});

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
		// bakRegion = screenshot();
		this.fire(new VisibilityEvent(true));

		Scene stage = getScene();
		if (stage != null) {
			removeListener(focusListener);
			if (previousKeyboardFocus != null && previousKeyboardFocus.getScene() == null) previousKeyboardFocus = null;
			Element actor = stage.getKeyboardFocus();
			if (actor == null || actor.isDescendantOf(this)) stage.setKeyboardFocus(previousKeyboardFocus);

			if (previousScrollFocus != null && previousScrollFocus.getScene() == null) previousScrollFocus = null;
			actor = stage.getScrollFocus();
			if (actor == null || actor.isDescendantOf(this)) stage.setScrollFocus(previousScrollFocus);
		}
		if (action != null) {
			addCaptureListener(ignoreTouchDown);
			addAction(Actions.sequence(action, Actions.removeListener(ignoreTouchDown, true), Actions.remove()));
		} else
			remove();

		if (this instanceof IDisposable) {
			all.remove(this);
		}
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
	public  Rect    lastRect        = new Rect();
	private boolean disabledActions = true;

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
		if (isMinimize) {
			boolean l = disabledActions;
			disabledActions = true;
			toggleMinimize();
			disabledActions = l;
		}
		isMaximize = !isMaximize;
		if (isMaximize) {
			if (!lastMin) {
				lastRect.set(x, y, width, height);
			}
			actions(Actions.sizeTo(graphics.getWidth(), graphics.getHeight(), disabledActions ? 0 : 0.06f),
			 Actions.moveTo(0, 0, disabledActions ? 0 : 0.01f));
			// setSize(Core.graphics.getWidth(), Core.graphics.getHeight());
			// setPosition(0, 0);
		} else {
			actions(Actions.sizeTo(lastRect.width, lastRect.height, disabledActions ? 0 : 0.06f),
			 Actions.moveTo(lastRect.x, lastRect.y, disabledActions ? 0 : 0.01f));
		}
		act(0);

		Timer.schedule(new Task() {
			@Override
			public void run() {
				if (!hasActions()) {
					maxlisteners.each(r -> r.fire(isMaximize));
					lastMaximize = isMaximize;
					cancel();
				}
			}
		}, 0, 0.01f, -1);
		// Time.runTask(0, () -> cont.invalidate());
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

			actions(
			 Actions.moveTo(x,
				y + lastRect.height - topHeight, disabledActions ? 0 : 0.01f),
			 Actions.sizeTo(getMinWidth(), topHeight, disabledActions ? 0 : 0.01f)
			);

			getCell(cont).set(BindCell.UNSET_CELL);
			cont.remove();
			if (!noButtons) {
				Tools.runIgnoredException(() -> {
					getCell(buttons).set(BindCell.UNSET_CELL);
					buttons.remove();
				});
			}
			sclListener.unbind();
			sizeChanged();
		} else {
			setup();

			left().top();
			if (isMaximize) {
				actions(Actions.sizeTo(graphics.getWidth(), graphics.getHeight(), disabledActions ? 0 : 0.1f),
				 Actions.moveTo(0, 0, disabledActions ? 0 : 0.01f));
			} else {
				actions(Actions.sizeTo(lastRect.width, lastRect.height, disabledActions ? 0 : 0.01f),
				 Actions.moveTo(lastRect.x = x,
					lastRect.y = y - lastRect.height + topHeight,
					disabledActions ? 0 : 0.01f));
				// y -= height - topHeight;
			}
			sclListener.rebind();
		}
		act(0);

		Timer.schedule(new Task() {
			public void run() {
				if (!hasActions()) {
					if (!isMaximize) display();
					minlisteners.each(r -> r.fire(isMinimize));
					cancel();
				}
			}
		}, 0, 0.01f, -1);
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

	public void draw() {
		topGroup.drawResidentTasks.forEach(task -> task.beforeDraw(this));
		Draw.alpha(parentAlpha);
		MyDraw.blurRect(x, y, width, height);
		Tools.runLoggedException(super::draw);
	}
	/** Adds a listener for back/escape keys to hide this dialog. */
	public void closeOnBack() {
		closeOnBack(() -> {});
	}

	public void closeOnBack(Runnable callback) {
		keyDown(key -> {
			if (key == KeyCode.escape || key == KeyCode.back) {
				Core.app.post(this::hide);
				callback.run();
			}
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
		return setPosition(Core.input.mouse());
	}

	public class FillTable extends Table {
		{
			fillParent = true;
			touchable = Touchable.enabled;
		}

		public Element hit(float x, float y, boolean touchable) {
			return Sr(super.hit(x, y, touchable))
			 .set(e -> e == this, titleTable)
			 .get();
		}
		public FillTable() {}
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

	/** 窗口会自动销毁 */
	public interface IDisposable {
		default void clearAll() {
			if (this instanceof Group) ((Group) this).find(e -> {
				if (e instanceof FilterTable) {
					Core.app.post(e::clear);
				}
				return false;
			});
		}
	}
	public interface IInfo {}
	/**
	 * 延迟几秒销毁的窗口
	 * @see InfoFadePopup
	 */
	public interface DelayDisposable extends IDisposable {
	}

	public static class DisWindow extends Window implements IDisposable {
		public DisWindow(String title, float minWidth, float minHeight, boolean full, boolean noButtons) {
			super(title, minWidth, minHeight, full, noButtons);
		}
		public DisWindow(String title, float width, float height, boolean full) {
			super(title, width, height, full);
		}
		public DisWindow(String title, float width, float height) {
			super(title, width, height);
		}
		public DisWindow(String title) {
			super(title);
		}
	}

	public String toString() {
		return ElementUtils.getElementName(this);
	}
	public static class NoTopWindow extends Window {

		public NoTopWindow(String title, float minWidth, float minHeight, boolean full, boolean noButtons) {
			super(title, minWidth, minHeight, full, noButtons);
		}
		public NoTopWindow(String title, float width, float height, boolean full) {
			super(title, width, height, full);
		}
		public NoTopWindow(String title, float width, float height) {
			super(title, width, height);
		}
		public NoTopWindow(String title) {
			super(title);
		}
		{
			moveListener.remove();
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
			getCells().remove(getCell(titleTable).clearElement(), true);
			Table table = new Table();
			table.setFillParent(true);
			table.top().add(titleTable).growX().height(titleHeight);
			titleTable.translation.y = titleHeight;
			table.setClip(true);
			addChild(table);
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
			translateTo(height - titleHeight <= y && element != null ? 0 : titleHeight);
			return element;
		}
		private void translateTo(float toValue) {
			if (last == null || toValue != last.getY()) applyAction(toValue);
		}
		private void applyAction(float toValue) {
			float time = last == null ? 0 : Math.max(0, last.getDuration() - last.getTime())/* 反过来 */;
			if (last != null) titleTable.removeAction(last);
			if (last == null) last = Actions.action(TranslateToAction.class, HopeFx.TranslateToAction::new);
			last.reset();
			last.setTime(time);
			last.setTranslation(0, toValue);
			last.setDuration(0.3f);
			last.setInterpolation(Interp.fastSlow);
			titleTable.addAction(last);
		}
	}
}