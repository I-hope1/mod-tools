package modtools.ui.content.ui.design;

import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.event.InputEvent.InputEventType;
import arc.scene.ui.Image;
import arc.scene.ui.layout.WidgetGroup;
import arc.struct.Seq;
import modtools.ui.content.ui.ShowUIList;
import modtools.utils.ElementUtils;

import static modtools.ui.components.linstener.ReferringMoveListener.snap;

public class DesignTable<T extends Group> extends Group {
	private Element selected;
	public  T       template;
	final   Vec2    delta = new Vec2(), last = new Vec2();
	float[] horizontalLines = new float[]{0, 0.5f, 1f}, verticalLines = new float[]{0, 0.5f, 1f};
	VirtualGroup virtualGroup = new VirtualGroup();
	public DesignTable(T template) {
		this.template = template;
		virtualGroup.addChild(template);
		init();
	}

	public float getPrefWidth() {
		return template.getPrefWidth();
	}
	public float getPrefHeight() {
		return template.getPrefHeight();
	}
	public void act(float delta) {
		super.act(delta);
		Vec2 pos = ElementUtils.getAbsolutePos(this);
		virtualGroup.setPosition(pos.x - x, pos.y - y);
		template.setSize(width, height);
		template.x = x;
		template.y = y;
	}
	private void init() {
		addCaptureListener(new InputListener() {
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (!moving) return false;
				selected = template.hit(x, y, false);
				if (selected == null) return false;
				if (button != KeyCode.mouseLeft) return virtualFire(event);
				selected.toFront();
				last.set(selected.x, selected.y);
				delta.set(x, y);
				return true;
			}
			public void touchDragged(InputEvent event, float x, float y, int pointer) {
				if (!moving) return;
				if (event.keyCode != KeyCode.mouseLeft) {
					virtualFire(event);
					return;
				}
				Vec2 vec2 = snap(selected, horizontalLines, verticalLines, last.x + x - delta.x, last.y + y - delta.y);
				selected.setPosition(vec2.x, vec2.y);
			}
			public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (!moving) return;
				if (button != KeyCode.mouseLeft) {
					virtualFire(event);
				}
			}
		});
	}
	public boolean virtualFire(InputEvent event) {
		if (event.type == InputEventType.touchDown && event.keyCode == KeyCode.mouseRight && selected instanceof Image i)
			i.setDrawable(Seq.with(ShowUIList.iconKeyMap.keySet()).random());
		return false;
	}
	public void addChild(Element actor) {
		actor.x = width / 2f;
		actor.y = height / 2f;
		template.addChild(actor);
	}
	public void draw() {
		template.draw();
	}
	// status
	public boolean moving = false;


	private static class VirtualGroup extends WidgetGroup {
		public void setScene(Scene stage) {
			super.setScene(stage);
		}
	}
}
