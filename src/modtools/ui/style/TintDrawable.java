package modtools.ui.style;

import arc.func.Intp;
import arc.scene.style.TextureRegionDrawable;

public class TintDrawable extends TextureRegionDrawable {
	int  last = tint.rgba();
	Intp intp;
	public TintDrawable(TextureRegionDrawable drawable, Intp intp) {
		super(drawable);
		this.intp = intp;
	}
	void updateTint() {
		if (last != intp.get()) tint.set(last = intp.get());
	}
	public void draw(float x, float y, float width, float height) {
		updateTint();
		super.draw(x, y, width, height);
	}
	public void draw(float x, float y, float originX, float originY, float width, float height, float scaleX,
									 float scaleY, float rotation) {
		updateTint();
		super.draw(x, y, originX, originY, width, height, scaleX, scaleY, rotation);
	}
}
