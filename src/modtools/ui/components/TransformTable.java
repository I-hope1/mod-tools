package modtools.ui.components;

import arc.graphics.g2d.Fill;
import arc.scene.*;
import arc.scene.event.Touchable;
import arc.scene.ui.layout.Table;
import arc.util.Tmp;
import modtools.utils.Tools;

/** 依靠在某一元素上 */
public class TransformTable extends Table {
	Group   group;
	Element target;
	int     lyingAlign;
	public TransformTable(Element target, int lyingAlign) {
		this(target, null, lyingAlign);
	}

	public TransformTable(Element target, Group transformParent, int lyingAlign) {
		if (target == null) throw new IllegalArgumentException("Target cannot be null");
		setTransform(true);
		this.target = target;
		this.lyingAlign = lyingAlign;
		group = new Group() { };
		group.touchable = Touchable.childrenOnly;
		group.addChild(this);
		group.fillParent = true;

		if (transformParent != null) {
			transformParent.addChild(group);
			pack();
		} else Tools.forceRun(() -> {
			if (group.parent == null && target.parent != null) {
				target.parent.addChild(group);
				pack();
				return true;
			}
			return false;
		});
	}
	public void act(float delta) {
		super.act(delta);

		localToAscendantCoordinates(group.parent,
		 Tmp.v1.set(target.getX(lyingAlign) - x,
		  target.getY(lyingAlign) - y));
		setPosition(Tmp.v1.x, Tmp.v1.y);
	}
	public void draw() {
		Fill.crect(x, y, 100, 100);
	}
}
