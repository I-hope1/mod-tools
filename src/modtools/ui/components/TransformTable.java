package modtools.ui.components;

import arc.scene.*;
import arc.scene.event.Touchable;
import arc.scene.ui.layout.*;
import modtools.utils.Tools;

/** 依靠在某一元素上 */
public class TransformTable extends Table {
	Group   group;
	Element target;
	int     align;
	public TransformTable(Element target, int align) {
		if (target == null) throw new IllegalArgumentException("Target cannot be null");
		this.target = target;
		this.align = align;
		align(align);
		group = new Group() {};
		group.touchable = Touchable.childrenOnly;
		group.addChild(this);
		group.fillParent = true;

		Tools.forceRun(() -> {
			if (group.parent == null && target.parent != null) {
				target.parent.addChild(group);
				layout();
				return true;
			}
			return false;
		});
	}
	public void act(float delta) {
		super.act(delta);

		setPosition(target.x, target.y);
	}
	public void draw() {
		super.draw();
	}
}
