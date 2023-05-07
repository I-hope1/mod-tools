package modtools.ui.components.limit;

import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.scene.style.Drawable;
import arc.scene.ui.Image;
import arc.util.Scaling;

public class LimitImage extends Image implements Limit {
	/**
	 * Creates an image with no region or patch, stretched, and aligned center.
	 */
	public LimitImage() {
	}

	public LimitImage(Drawable name, Color color) {
		super(name, color);
	}

	/**
	 * Creates an image stretched, and aligned center.
	 *
	 * @param patch May be null.
	 */
	public LimitImage(NinePatch patch) {
		super(patch);
	}

	/**
	 * Creates an image stretched, and aligned center.
	 *
	 * @param region May be null.
	 */
	public LimitImage(TextureRegion region) {
		super(region);
	}

	/**
	 * Creates an image stretched, and aligned center.
	 *
	 * @param texture
	 */
	public LimitImage(Texture texture) {
		super(texture);
	}

	/**
	 * Creates an image stretched, and aligned center.
	 *
	 * @param drawable May be null.
	 */
	public LimitImage(Drawable drawable) {
		super(drawable);
	}

	/**
	 * Creates an image aligned center.
	 *
	 * @param drawable May be null.
	 * @param scaling
	 */
	public LimitImage(Drawable drawable, Scaling scaling) {
		super(drawable, scaling);
	}

	/**
	 * @param drawable May be null.
	 * @param scaling
	 * @param align
	 */
	public LimitImage(Drawable drawable, Scaling scaling, int align) {
		super(drawable, scaling, align);
	}

	public void updateVisibility() {
		visible = Limit.isVisible(this);
		// if (visible) draw();
	}
}
