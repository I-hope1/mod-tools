package modtools.ui.components.limit;

import arc.graphics.g2d.TextureRegion;
import arc.scene.style.Drawable;
import arc.scene.ui.ImageButton;

public class LimitImageButton extends ImageButton implements Limit {
	public LimitImageButton() {}
	public LimitImageButton(Drawable icon, ImageButtonStyle stylen) {
		super(icon, stylen);
	}
	public LimitImageButton(TextureRegion region) {
		super(region);
	}
	public LimitImageButton(TextureRegion region, ImageButtonStyle stylen) {
		super(region, stylen);
	}
	public LimitImageButton(ImageButtonStyle style) {
		super(style);
	}
	public LimitImageButton(Drawable imageUp) {
		super(imageUp);
	}
	public LimitImageButton(Drawable imageUp, Drawable imageDown) {
		super(imageUp, imageDown);
	}
	public LimitImageButton(Drawable imageUp, Drawable imageDown, Drawable imageChecked) {
		super(imageUp, imageDown, imageChecked);
	}

	public void updateVisibility() {
		visible = Limit.isVisible(this);
	}
}
