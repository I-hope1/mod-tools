package modtools.ui.components.buttons;

import arc.graphics.g2d.TextureRegion;
import arc.scene.Element;
import arc.scene.event.Touchable;
import arc.scene.style.Drawable;
import arc.scene.ui.ImageButton;
import arc.util.*;

public class CircleImageButton extends ImageButton {
	public Element hit(float x, float y, boolean touchable) {
		if (touchable && this.touchable == Touchable.disabled) return null;
		return super.hit(x, y, touchable);
	}

	public CircleImageButton() {
	}
	public CircleImageButton(Drawable icon, ImageButtonStyle stylen) {
		super(icon, stylen);
	}
	public CircleImageButton(TextureRegion region) {
		super(region);
	}
	public CircleImageButton(TextureRegion region, ImageButtonStyle stylen) {
		super(region, stylen);
	}
	public CircleImageButton(ImageButtonStyle style) {
		super(style);
	}
	public CircleImageButton(Drawable imageUp) {
		super(imageUp);
	}
	public CircleImageButton(Drawable imageUp, Drawable imageDown) {
		super(imageUp, imageDown);
	}
	public CircleImageButton(Drawable imageUp, Drawable imageDown,
													 Drawable imageChecked) {
		super(imageUp, imageDown, imageChecked);
	}
}
