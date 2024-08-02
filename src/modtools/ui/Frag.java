package modtools.ui;

import arc.*;
import arc.graphics.Color;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.actions.Actions;
import arc.scene.event.Touchable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.annotations.settings.*;
import modtools.events.ISettings;
import modtools.ui.comp.Hitter;
import modtools.ui.comp.limit.LimitTable;
import modtools.ui.comp.linstener.MoveListener;
import modtools.ui.content.Content;
import modtools.utils.*;
import modtools.utils.ui.LerpFun;
import modtools.utils.ui.search.BindCell;

import static modtools.IntVars.modName;
import static modtools.ui.Frag.Settings.position;
import static modtools.ui.IntUI.*;

/** @author I-hope1 */
public class Frag extends Table {
	public static final Color defaultColor = Color.sky;

	public boolean    hideCont = false;
	public ScrollPane container;
	BindCell cell;
	Group    circle;
	Image    top;

	ObjectSet<Content> enabledContents = new OrderedSet<>();
	public Frag() {
		super(Styles.black8);
	}

	MoveInsideListener listener;
	public void load() {
		touchable = Touchable.enabled;
		//		MyPacket.register();
		name = modName + "-frag";
		top = new Image();
		top.addListener(new ITooltip(() -> IntUI.tips("frag")));
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
		cell = BindCell.ofConst(add(container).size(120, 40 * 5 + 1));

		left().bottom();
		topGroup.addChild(this);
		Log.info("Initialize TopGroup.");

		// focusListener = new CancelFocusListener();
		// container.addCaptureListener(focusListener);

		listener = new MoveInsideListener();
		// 添加双击变小
		EventHelper.doubleClick(top, () -> {
			if (!hideCont) return;
			if (circle == null) {
				addChild(circle = new CircleGroup());
			}
			if (circle.getChildren().any()) {
				circleRemove();
			} else {
				circleBuild();
			}
		}, () -> {
			cell.toggle(hideCont);
			hideCont = !hideCont;
			invalidate();
			circleRemove();
			Core.app.post(() -> listener.display(x, y, false));
		});
		IntVars.addResizeListener(() -> {
			Vec2 pos = position.getPosition();
			listener.display(pos.x, pos.y, false);
		});
		Vec2 pos = position.getPosition();
		listener.display(pos.x, pos.y, false);
	}
	public final float hoverSize   = 45 * Scl.scl();
	public final float hoverRadius = 96 * Scl.scl();
	private void circleBuild() {
		float angle          = 90;
		int   angleReduction = 360 / enabledContents.size;

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
				image.setPosition(
				 top.getWidth() / 2f + radius * Mathf.cosDeg(rotation1),
				 top.getHeight() / 2f + radius * Mathf.sinDeg(rotation1), Align.center);
			});
			image.clicked(content::build);

			circle.addChild(image);
		}

		top.addAction(Actions.color(Color.lightGray, 0.1f));
	}
	private void circleRemove() {
		if (circle == null) return;
		top.addAction(Actions.color(defaultColor, 0.1f));
		for (Element child : circle.getChildren()) {
			if (child.hasActions()) continue;
			child.actions(Actions.moveToAligned(
				top.getWidth() / 2f,
				top.getHeight() / 2f, Align.center,
				0.1f, Interp.smooth),
			 Actions.remove());
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
		public void display(float x, float y, boolean writeSetting) {
			float mainWidth  = main.getPrefWidth(), mainHeight = main.getPrefHeight();
			float touchWidth = touch.getWidth(), touchHeight = touch.getHeight();
			main.setPosition(
			 Mathf.clamp(x, -touchWidth / 3f, Core.graphics.getWidth() - mainWidth / 2f),
			 Mathf.clamp(y, -mainHeight + touchHeight, Core.graphics.getHeight() - mainHeight)
			);
			if (writeSetting) position.set(STR."(\{main.x},\{main.y})");
		}

		public void display(float x, float y) {
			display(x, y, true);
		}
	}

	@SettingsInit
	public enum Settings implements ISettings {
		@Switch
		position(Position.class);

		Settings(Class<?> c, float... args) { }

		static {
			position.defSwitchOn(true);
		}
	}
	private static class CircleGroup extends Group {
		public Element hit(float x, float y, boolean touchable) {
			// Log.info("@, @",x,y);
			return super.hit(x, y, touchable);
		}
	}
}
