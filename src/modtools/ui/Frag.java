package modtools.ui;

import arc.*;
import arc.graphics.Color;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.actions.Actions;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.annotations.settings.*;
import modtools.events.ISettings;
import modtools.ui.IntUI.IMenu;
import modtools.ui.components.Hitter;
import modtools.ui.components.limit.LimitTable;
import modtools.ui.components.linstener.*;
import modtools.ui.content.Content;
import modtools.utils.Tools;
import modtools.utils.ui.LerpFun;
import modtools.utils.ui.search.BindCell;

import java.util.*;

import static modtools.IntVars.modName;
import static modtools.ui.Frag.Settings.position;
import static modtools.ui.IntUI.*;

public class Frag extends Table {
	public static final Color defaultColor = Color.sky;

	public boolean    hideCont = false;
	public ScrollPane container;
	BindCell cell;
	Group    circle;
	Image    top;

	Set<Content> enabledContents = new HashSet<>();
	public Frag() {
		super(Styles.black8);
	}
	public void load() {
		touchable = Touchable.enabled;
		//		MyPacket.register();
		name = modName + "-frag";
		top = new Image();
		add(top).color(defaultColor)
		 .margin(0).pad(0)
		 .fillX().minWidth(40).height(40);
		update(this::pack);
		row();

		container = new ScrollPane(new LimitTable(table -> {
			if (Content.all.isEmpty()) Contents.load();
			Content.all.forEach(content -> {
				if (content == null || !content.loadable()) return;
				enabledContents.add(content);
				table.image().color(Pal.gray).growX().row();
				table.add(content.buildButton(false))
				 .marginLeft(6f)
				 .size(120, 40);
				Events.fire(content);
				if (content.loadable()) Tools.runLoggedException(content::load);
				table.row();
			});
		}), HopeStyles.noBarPane);
		// lastIndex = getCells().indexOf(cell);
		container.update(() -> container.setOverscroll(false, false));
		cell = new BindCell(add(container).size(120, 40 * 5 + 1));

		left().bottom();
		topGroup.addChild(this);
		if (position.isSwitchOn()) {
			Vec2 pos = position.getPosition();
			setPosition(pos.x, pos.y);
		}
		Log.info("Initialize TopGroup.");

		// focusListener = new CancelFocusListener();
		// container.addCaptureListener(focusListener);

		var listener = new MoveInsideListener();
		// 添加双击变小
		IntUI.doubleClick(top, () -> {
			if (!hideCont) return;
			if (circle == null) addChild(circle = new Group() {{
				update(() -> {
					width = top.getWidth();
					height = top.getHeight();
					setPosition(0, 0, Align.center);
				});
			}});
			if (circle.getChildren().any()) circleRemove();
			else circleBuild();
		}, () -> {
			cell.toggle(hideCont);
			hideCont = !hideCont;
			invalidate();
			circleRemove();
			Core.app.post(() -> listener.display(x, y));
		});
		IntVars.addResizeListener(() -> listener.display(x, y));
	}
	public final float hoverSize   = 45 * Scl.scl();
	public final float hoverRadius = 96 * Scl.scl();
	private void circleBuild() {
		float angle          = 90;
		int   angleReduction = 360 / enabledContents.size();

		float toX = Mathf.clamp(x, hoverRadius + width / 4f, Core.graphics.getWidth() - hoverRadius - width / 4f);
		float toY = Mathf.clamp(y, hoverRadius + height / 4f, Core.graphics.getHeight() - hoverRadius - height / 4f);

		addAction(Actions.moveTo(toX, toY, 0.1f));
		for (Content content : enabledContents) {
			ImageButton image      = (ImageButton) content.buildButton(true);
			float       finalAngle = angle;
			angle -= angleReduction;
			new LerpFun(Interp.smooth).onUI().registerDispose(1 / 24f, f -> {
				float rotation1 = Mathf.lerp(0, finalAngle, f);
				// image.setRotation(rotation1);
				float radius = Mathf.lerp(0, hoverRadius, f);
				Core.graphics.requestRendering();
				image.setSize(hoverSize);
				image.setOrigin(Align.center);
				image.setPosition(
				 top.getWidth() / 2f + radius * Mathf.cosDeg(rotation1),
				 top.getHeight() / 2f + radius * Mathf.sinDeg(rotation1));/* Align.center); */
			});
			image.clicked(content::build);

			circle.addChild(image);
		}

		top.actions(Actions.color(Color.pink, 0.1f));
	}
	private void circleRemove() {
		if (circle == null) return;
		top.actions(Actions.color(defaultColor, 0.1f));
		for (Element child : circle.getChildren()) {
			if (child.hasActions()) continue;
			child.actions(Actions.moveTo(
			 top.getWidth() / 2f,
			 top.getHeight() / 2f, 0.1f,
			 Interp.smooth), Actions.remove());
		}
	}

	/** 自动解除focus */
	public Element hit(float x, float y, boolean touchable) {
		Element hit = super.hit(x, y, touchable);
		if (hit == null && this instanceof IMenu) hit = Hitter.firstTouchable();
		if (hit == null) {
			if (Core.scene.getScrollFocus() != null && Core.scene.getScrollFocus().isDescendantOf(this))
				Core.scene.setScrollFocus(null);
			if (Core.scene.getKeyboardFocus() != null && Core.scene.getKeyboardFocus().isDescendantOf(this)) {
				Core.scene.setKeyboardFocus(null);
			}
		}
		return hit;
	}
	private class MoveInsideListener extends MoveListener {
		public MoveInsideListener() { super(Frag.this.top, Frag.this); }
		public void display(float x, float y) {
			float mainWidth  = main.getPrefWidth(), mainHeight = main.getPrefHeight();
			float touchWidth = touch.getWidth(), touchHeight = touch.getHeight();
			main.setPosition(
			 Mathf.clamp(x, -touchWidth / 3f, Core.graphics.getWidth() - mainWidth / 2f),
			 Mathf.clamp(y, -mainHeight + touchHeight, Core.graphics.getHeight() - mainHeight)
			);
		}
	}
	public void setPosition(float x, float y) {
		super.setPosition(x, y);
		position.set(STR."(\{x},\{y})");
	}

	@SettingsInit
	public enum Settings implements ISettings {
		position(Position.class, 0, 0, 1, 1);

		Settings(Class<?> c, float... args) { }

		static {
			position.defSwitchOn(false);
		}
	}
}
