package modtools.ui.comp.buttons;

import arc.scene.Element;
import arc.scene.event.Touchable;
import arc.scene.style.Drawable;
import arc.scene.ui.ImageButton;
import arc.util.*;

public class CircleImageButton extends ImageButton {
	public Element hit(float x, float y, boolean touchable) {
		if (touchable && this.touchable == Touchable.disabled) return null;
		return Tmp.cr1.set(
			translation.x + getX(Align.center),
			translation.y + getY(Align.center),
			getWidth()).contains(x, y) ? this : null;
	}

	public CircleImageButton(Drawable icon, ImageButtonStyle style) {
		super(icon, style);
	}

	/* public CircleImageButton() {
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
	} */
}
