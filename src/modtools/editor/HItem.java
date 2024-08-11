package modtools.editor;

import arc.graphics.g2d.Fill;

public interface HItem {
	float x();
	float y();
	float width();
	float height();
	default void draw() {
		Fill.crect(x(), y(), width(), height());
	};
}
