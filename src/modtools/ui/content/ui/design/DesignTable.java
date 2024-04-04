package modtools.ui.content.ui.design;

import arc.func.Cons;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.layout.WidgetGroup;
import modtools.utils.ElementUtils;

import static modtools.ui.components.linstener.ReferringMoveListener.snap;

public class DesignTable<T extends Group> extends WidgetGroup {
	private Element selected;
	public  T       template;
	final   Vec2    delta = new Vec2(), last = new Vec2();
	float[] horizontalLines = new float[]{0, 0.5f, 1f}, verticalLines = new float[]{0, 0.5f, 1f};
	VirtualGroup virtualGroup = new VirtualGroup();
	public DesignTable(T template) {
		this.template = template;
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
		if (template.parent == this) {
			template.setSize(width, height);
			template.x = 0;
			template.y = 0;
			return;
		}
		Vec2 pos = ElementUtils.getAbsolutePos(this);
		virtualGroup.setPosition(pos.x - x, pos.y - y);
		template.setSize(width, height);
		template.x = x;
		template.y = y;
		template.act(delta);
	}
	private void init() {
		changeStatus(Status.move);
		addCaptureListener(new InputListener() {
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (status == Status.edit) return false;
				selected = template.hit(x, y, false);
				if (selected == null) return false;
				while (selected.parent != template && selected.parent != DesignTable.this) {
					selected = selected.parent;
					if (selected == null) return false;
				}
				if (button != KeyCode.mouseLeft) return false;
				if (status == Status.delete) {
					selected.remove();
					return false;
				}
				selected.toFront();
				last.set(selected.x, selected.y);
				delta.set(x, y);
				return true;
			}
			public void touchDragged(InputEvent event, float x, float y, int pointer) {
				if (status != Status.move) return;
				if (event.keyCode != KeyCode.mouseLeft) return;
				Vec2 vec2 = snap(selected, horizontalLines, verticalLines, last.x + x - delta.x, last.y + y - delta.y);
				selected.setPosition(vec2.x, vec2.y);
			}
		});
	}

	public void addChild(Element actor) {
		actor.x = width / 2f;
		actor.y = height / 2f;
		template.addChild(actor);
	}
	public void draw() {
		if (template.parent == virtualGroup) template.draw();
		else super.draw();
	}
	// status
	Status status;
	public void changeStatus(Status status) {
		this.status = status;
		status.listener.get(this);
	}
	void super$addChild(Element actor) {
		super.addChild(actor);
	}
	public enum Status {
		move(t -> t.virtualGroup.addChild(t.template)),
		edit(t -> t.super$addChild(t.template)),
		delete(t -> t.virtualGroup.addChild(t.template));

		final Cons<DesignTable<?>> listener;
		Status(Cons<DesignTable<?>> listener) {
			this.listener = listener;
		}
	}
	private static class VirtualGroup extends WidgetGroup {
		public void setScene(Scene stage) {
			super.setScene(stage);
		}
	}
}
