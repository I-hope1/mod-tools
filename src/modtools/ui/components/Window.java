package modtools.ui.components;

import arc.Core;
import arc.func.Prov;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.math.Interp;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.Action;
import arc.scene.Element;
import arc.scene.Scene;
import arc.scene.actions.Actions;
import arc.scene.event.*;
import arc.scene.style.Drawable;
import arc.scene.style.NinePatchDrawable;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Time;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.ui.Styles;

import static modtools.ui.IntUI.icons;

public class Window extends Table {
	public static Drawable myPane = ((NinePatchDrawable) Tex.pane).tint(new Color(1, 1, 1, 0.8f));
	public static final Cell emptyCell = new Cell<>();
	public Table top = new Table(myPane), cont = new Table(myPane), buttons = new Table(myPane);
	public float topAndBottomHeight;
	public float minWidth, minHeight;
	// 用于最小化时的最小宽度
	private static final float topWdith = 160, topHeight = 45;
	public Label title;
	public boolean
			// 是否置顶
			sticky = false,
			full, noButtons;
	public MoveListener moveListener;
	public SclLisetener sclLisetener;

	public Window(String title, float minWidth, float minHeight, boolean full, boolean noButtons) {
		super(Styles.none);
		addListener(new InputListener() {
			@Override
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				setZIndex(Integer.MAX_VALUE);
				return false;
			}
		});
		touchable = top.touchable = cont.touchable = Touchable.enabled;
		top.margin(0);
		cont.margin(8f);
		buttons.margin(0);
		this.minWidth = minWidth;
		this.minHeight = minHeight;
		this.full = full;
		this.noButtons = noButtons;

//		top.defaults().width(winWidth);

		left().defaults().left();

		add(top).growX().height(topHeight).row();
		moveListener = new MoveListener(top, this);
		this.title = top.add(title).grow().padLeft(10f).padRight(10f).get();
		if (full) {
			top.button(icons.get("sticky"), Styles.clearNoneTogglei, 32, () -> {
				sticky = !sticky;
			}).padLeft(4f);
			top.button(Icon.down, Styles.clearNoneTogglei, 32, this::minimize).update(b -> {
				b.setChecked(isMinimize);
			}).padLeft(4f);
			ImageButton button = top.button(Tex.whiteui, Styles.flati, 32, this::maximize).padLeft(4f).get();
			button.update(() -> {
				button.getStyle().imageUp = isMaximize ? icons.get("normal") : icons.get("maximize");
			});
		}
		top.button(Icon.cancel, Styles.clearNonei, 32, this::hide).padLeft(4f).padRight(4f);
//		cont.defaults().height(winHeight);
		setup();

		moveListener.fire = () -> {
			if (isMaximize && !isMinimize) maximize();
		};
		Time.runTask(1, () -> {
			sclLisetener = new SclLisetener(this, minWidth, minHeight);
			update(() -> {
				if (sticky) setZIndex(Integer.MAX_VALUE);
				sclLisetener.disabled = isMaximize;
				moveListener.disabled = sclLisetener.scling;
			});
		});
		super.update(() -> {
			for (Runnable r : runs) r.run();
		});
	}

	Seq<Runnable> runs = new Seq<>();
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
		setPosition(Mathf.clamp(x, -touchWidth / 3f, Core.graphics.getWidth() - mainWidth + touchWidth / 2f),
				Mathf.clamp(y, -mainHeight + touchHeight / 3f * 2f, Core.graphics.getHeight() - mainHeight));
	}


	public float getPrefWidth() {
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
	;

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
		stage.add(this);
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
			if (isMinimize) minimize();
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

	public void maximize() {
		if (isMinimize) minimize();
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

	public void minimize() {
		isMinimize = !isMinimize;
		if (isMinimize) {
			if (!isMaximize) {
				lastWidth = getWidth();
				lastHeight = getHeight();
			}

			width = topWdith;
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

	public interface MinimizedListener {
		void fire();
	}
}
