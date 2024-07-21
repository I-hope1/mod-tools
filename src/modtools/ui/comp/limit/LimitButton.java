package modtools.ui.comp.limit;

import arc.scene.style.Drawable;
import arc.scene.ui.Button;

public class LimitButton extends Button implements Limit {
	public LimitButton(ButtonStyle style) {
		super(style);
	}
	public LimitButton() {
	}
	public LimitButton(Drawable up) {
		super(up);
	}
	public LimitButton(Drawable up, Drawable down) {
		super(up, down);
	}
	public LimitButton(Drawable up, Drawable down, Drawable checked) {
		super(up, down, checked);
	}
	public void updateVisibility() {
		visible = Limit.isVisible(this);
	}
}
