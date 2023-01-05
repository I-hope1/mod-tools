package modtools.ui.components;

import arc.*;
import arc.func.*;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.math.Interp;
import arc.math.Mathf;
import arc.math.geom.Position;
import arc.math.geom.Vec2;
import arc.scene.Action;
import arc.scene.Element;
import arc.scene.Scene;
import arc.scene.actions.Actions;
import arc.scene.event.*;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.ImageButton.ImageButtonStyle;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.struct.*;
import arc.util.*;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.ui.*;

import static modtools.IntVars.topGroup;
import static modtools.ui.Contents.windowManager;
import static modtools.ui.IntUI.*;

/**
 * 浮动的窗口，可以缩放，最小化，最大化
 *
 * @author I hope...
 **/
public class Window extends Table {
	public static final ObjectSet<Window> all = new ObjectSet<>() {
		public boolean add(Window value) {
			boolean b = super.add(value);
			if (windowManager != null && windowManager.ui != null && windowManager.ui.isShown())
				windowManager.rebuild();
			return b;
		}

		public boolean remove(Window value) {
			boolean ok = super.remove(value);
			if (windowManager != null && windowManager.ui != null && windowManager.ui.isShown())
				windowManager.rebuild();
			return ok;
		}
	};

	static Window focusWindow;

	static {
		IntVars.addResizeListener(() -> all.each(Window::display));

		Events.run(Trigger.update, () -> {
			var children = Core.scene.root.getChildren();
			for (int i = children.size - 1; i >= 0; i--) {
				if (children.get(i) instanceof Window) {
					focusWindow = (Window) children.get(i);
					break;
				}
			}
			/*while (focus != null) {
				if (focus instanceof Window) {
					focusWindow = (Window) focus;
					break;
				}
				focus = focus.parent;
			}*/
		});
	}

	public static Drawable myPane = Tex.pane;
	// ((NinePatchDrawable) Tex.pane).tint(new Color(1, 1, 1, 0.9f));
	public static final Cell emptyCell = new Cell<>();
	public Table top = new Table(myPane) {
		@Override
		public Cell<ImageButton> button(Drawable icon, ImageButtonStyle style, float isize, Runnable listener) {
			var cell = super.button(icon, style, isize, listener);
			cell.get().tapped(() -> {
				moveListener.disabled = true;
				Time.runTask(0, () -> moveListener.disabled = false);
			});
			return cell;
		}
	},
			cont = new Table(myPane),
			buttons = new Table(myPane);
	public float minWidth, minHeight;
	// 用于最小化时的最小宽度
	private static final float topHeight = 45;
	public Label title;
	public boolean
			// 是否置顶
			sticky = false,
			full, noButtons;
	public MoveListener moveListener;
	public SclLisetener sclLisetener;

	public Window(String title, float minWidth, float minHeight, boolean full, boolean noButtons) {
		super(Styles.none);

		cont.setClip(true);
		tapped(this::toFront);
		touchable = top.touchable = cont.touchable = Touchable.enabled;
		top.margin(0);
		if (OS.isWindows) IntUI.doubleClick(top, () -> {}, this::toggleMaximize);
		cont.margin(8f);
		buttons.margin(0);
		this.minHeight = minHeight;
		this.full = full;
		this.noButtons = noButtons;

		left().defaults().left();

		add(top).growX().height(topHeight).row();
		moveListener = new MoveListener(top, this);
		this.title = top.add(title).grow().padLeft(10f).padRight(10f).update(l -> {
			// var children = Core.scene.root.getChildren();
			// l.setColor(children.peek() == this || (children.size >= 2 && children.get(children.size - 2) == this) ? Color.white : Color.lightGray);
			l.setColor(focusWindow == this ? Color.white : Color.lightGray);
		}).get();
		if (full) {
			top.button(icons.get("sticky"), IntStyles.clearNoneTogglei, 32, () -> {
				sticky = !sticky;
			}).checked(b -> sticky).padLeft(4f).name("sticky");
			top.button(Icon.down, IntStyles.clearNoneTogglei, 32, this::toggleMinimize).update(b -> {
				b.setChecked(isMinimize);
			}).padLeft(4f);
			ImageButton button = top.button(Tex.whiteui, IntStyles.clearNonei, 32, this::toggleMaximize).disabled(b -> !isShown()).padLeft(4f).get();
			button.update(() -> {
				button.getStyle().imageUp = isMaximize ? icons.get("normal") : icons.get("maximize");
			});
		}
		top.button(Icon.cancel, IntStyles.clearNonei, 32, this::hide).padLeft(4f).padRight(4f);
		//		cont.defaults().height(winHeight);
		setup();

		sclLisetener = new SclLisetener(this, this.minWidth, minHeight);
		moveListener.fire = () -> {
			if (isMaximize && !isMinimize) {
				float mulx = moveListener.bx / width;
				toggleMaximize();
				moveListener.bx = width * mulx;
				x -= moveListener.bx;
			}
		};
		keyDown(k -> {
			if (k == KeyCode.f4 && Core.input.ctrl()) {
				hide();
			}
		});

		Time.runTask(1, () -> {
			// 默认最小宽度为pref宽度
			this.minWidth = Math.max(minWidth, getMinWidth());
			sclLisetener.set(this.minWidth, minHeight);
		});
		super.update(() -> {
			for (Runnable r : runs) r.run();
			if (sticky) toFront();
			sclLisetener.disabled = isMaximize;
			moveListener.disabled = sclLisetener.scling;
		});

		all.add(this);
	}

	ObjectSet<Runnable> runs = new ObjectSet<>();

	@Override
	public Element update(Runnable r) {
		runs.add(r);
		return this;
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

	public void setup() {
		add(cont).grow().row();
		if (!noButtons) add(buttons).growX().row();
	}

	public void noButtons(boolean val) {
		if (noButtons == val) return;
		noButtons = val;
		if (noButtons) {
			buttons.remove();
			//			buttons.clear();
			//			buttons = null;
		} else {
			if (buttons.parent != null) {
				buttons.remove();
			}
			add(buttons).growX().row();
		}
	}

	public void display() {
		float mainWidth = getWidth(), mainHeight = getHeight();
		float touchWidth = top.getWidth(), touchHeight = top.getHeight();
		super.setPosition(Mathf.clamp(x, -touchWidth / 3f, Core.graphics.getWidth() - mainWidth + touchWidth / 2f),
				Mathf.clamp(y, -mainHeight + touchHeight / 3f * 2f, Core.graphics.getHeight() - mainHeight));
		if (isMaximize) {
			// false取反为true
			isMaximize = false;
			toggleMaximize();
		}
	}


	public float getPrefWidth() {
		// 默认最小宽度为顶部的最小宽度
		return Mathf.clamp(super.getPrefWidth(), minWidth, Core.graphics.getWidth());
	}

	public float getPrefHeight() {
		return Mathf.clamp(super.getPrefHeight(), minHeight, Core.graphics.getHeight());
	}

	private static final Prov<Action>
			defaultShowAction = () -> Actions.sequence(Actions.alpha(0), Actions.fadeIn(0.2f, Interp.fade)),
			defaultHideAction = () -> Actions.fadeOut(0.2f, Interp.fade);
	protected InputListener ignoreTouchDown = new InputListener() {
		@Override
		public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
			event.cancel();
			return false;
		}
	};
	Element previousKeyboardFocus, previousScrollFocus;
	FocusListener focusListener = new FocusListener() {
		@Override
		public void keyboardFocusChanged(FocusEvent event, Element actor, boolean focused) {
			if (!focused) focusChanged(event);
		}

		@Override
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


	@Override
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
		setOrigin(Align.center);
		setClip(false);
		setTransform(true);

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
		stage.setKeyboardFocus(this);
		stage.setScrollFocus(this);

		if (action != null) addAction(action);
		pack();

		return this;
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
		display();
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
	}

	/**
	 * Hides the dialog. Called automatically when a button is clicked. The default implementation fades out the dialog over 400
	 * milliseconds.
	 */
	public void hide() {
		if (!isShown()) return;
		setOrigin(Align.center);
		setClip(false);
		setTransform(true);

		hide(defaultHideAction.get());
	}

	public boolean isMaximize = false;
	public Vec2 lastPos = new Vec2();

	public void toggleMaximize() {
		if (isMinimize) toggleMinimize();
		isMaximize = !isMaximize;
		if (isMaximize) {
			lastWidth = getWidth();
			lastHeight = getHeight();
			lastPos.set(x, y);
			setSize(Core.graphics.getWidth(), Core.graphics.getHeight());
			setPosition(0, 0);
		} else {
			setSize(lastWidth, lastHeight);
			setPosition(lastPos.x, lastPos.y);
		}
	}

	public Seq<MinimizedListener> mlisteners = new Seq<>();
	public boolean isMinimize = false;
	// 用于存储最小/大化前的宽度和高度
	public float lastWidth, lastHeight;

	public void toggleMinimize() {
		isMinimize = !isMinimize;
		if (isMinimize) {
			if (!isMaximize) {
				lastWidth = getWidth();
				lastHeight = getHeight();
			}

			width = getMinWidth();
			height = topHeight;
			y += lastHeight - height;

			getCell(cont).set(emptyCell);
			cont.remove();
			if (!noButtons) {
				try {
					getCell(buttons).set(emptyCell);
					buttons.remove();
				} catch (Throwable ignored) {}
			}
			removeListener(sclLisetener);
			x = Math.max(x, lastWidth / 2f);
			sizeChanged();
		} else {
			setup();

			if (isMaximize) {
				x = y = 0;
				setSize(Core.graphics.getWidth(), Core.graphics.getHeight());
			} else {
				setSize(lastWidth, lastHeight);
				y -= height - topHeight;
			}
			addListener(sclLisetener);
		}
		display();
		mlisteners.each(MinimizedListener::fire);
	}

	/**
	 * Adds a minimized() listener.
	 */
	public void minimized(Runnable run) {
		mlisteners.add(run::run);
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

	public void setPosition(Position pos) {
		setPosition(pos.getX(), pos.getY());
		display();
	}

	public interface MinimizedListener {
		void fire();
	}

	public static class DisposableWindow extends Window {

		public DisposableWindow(String title, float minWidth, float minHeight, boolean full, boolean noButtons) {
			super(title, minWidth, minHeight, full, noButtons);
		}

		public DisposableWindow(String title, float width, float height, boolean full) {
			super(title, width, height, full);
		}

		public DisposableWindow(String title, float width, float height) {
			super(title, width, height);
		}

		public DisposableWindow(String title) {
			super(title);
		}

		@Override
		public void hide() {
			super.hide();
			all.remove(this);
			clearChildren();
		}
	}
}
