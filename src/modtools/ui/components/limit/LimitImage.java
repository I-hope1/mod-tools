package modtools.ui.components.limit;

import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.scene.style.Drawable;
import arc.scene.ui.Image;
import arc.util.Scaling;

public class LimitImage extends Image implements Limit {
	public LimitImage() {
	}
	public LimitImage(Drawable name, Color color) {
		super(name, color);
	}
	public LimitImage(NinePatch patch) {
		super(patch);
	}
	public LimitImage(TextureRegion region) {
		super(region);
	}
	public LimitImage(Texture texture) {
		super(texture);
	}
	public LimitImage(Drawable drawable) {
		super(drawable);
	}
	public LimitImage(Drawable drawable, Scaling scaling) {
		super(drawable, scaling);
	}
	public LimitImage(Drawable drawable, Scaling scaling, int align) {
		super(drawable, scaling, align);
	}

	public void updateVisibility() {
		visible = Limit.isVisible(this);
	}
}
