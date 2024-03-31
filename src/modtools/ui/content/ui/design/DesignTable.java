package modtools.ui.content.ui.design;

import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.layout.WidgetGroup;
import modtools.utils.ElementUtils;

import static modtools.ui.components.linstener.ReferringMoveListener.snap;

public class DesignTable<T extends Group> extends Element {
	private Element selected;
	public  T       template;
	final   Vec2    delta = new Vec2(), last = new Vec2();
	float[] horizontalLines = new float[]{0, 0.5f, 1f}, verticalLines = new float[]{0, 0.5f, 1f};
	Group virtualParent = new WidgetGroup();
	public DesignTable(T template) {
		this.template = template;
		virtualParent.addChild(template);
		init();
	}
	private void init() {
		template.x = width / 2f;
		template.y = height / 2f;
		addCaptureListener(new InputListener() {
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				selected = template.hit(x, y, false);
				if (selected == null) return false;
				last.set(selected.x, selected.y);
				delta.set(x, y);
				return true;
			}
			public void touchDragged(InputEvent event, float x, float y, int pointer) {
				Vec2 vec2 = snap(selected, horizontalLines, verticalLines, last.x + x - delta.x, last.y + y - delta.y);
				selected.setPosition(vec2.x, vec2.y);
			}
			public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
				event.cancel();
			}
		});
	}
	public void addChild(Element actor) {
		template.addChild(actor);
	}
	public void sizeChanged() {
		super.sizeChanged();
		template.setSize(width, height);
	}
	public void draw() {
		Vec2 pos = ElementUtils.getAbsolutePos(this);
		virtualParent.setPosition(pos.x, pos.y);
		template.draw();
	}
}
