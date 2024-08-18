package modtools.ui.comp.limit;

import arc.scene.ui.TextButton;

import static modtools.ui.comp.limit.Limit.isVisible;

public class LimitTextButton extends TextButton implements Limit{
	public LimitTextButton(String text) {
		super(text);
	}

	public LimitTextButton(String text, TextButtonStyle style) {
		super(text, style);
	}

	/*
	public boolean isVisible() {
		Element elem = parent;
		while (!(elem instanceof ScrollPane)) {
			elem = elem.parent;
			if (elem == null) return false;
		}
		localToAscendantCoordinates(elem, Tmp.v1.set(0, 0));
		// localToStageCoordinates(Tmp.v1.set(0, 0));
		float w = width, h = height;
		if (Tmp.v1.x < -w || Tmp.v1.y < -h) return false;
		// localToAscendantCoordinates(elem, Tmp.v2.set(w, h));
		// localToStageCoordinates(Tmp.v1.set(w, h));
		return !(Tmp.v1.x > elem.getWidth()) && !(Tmp.v1.y > elem.getHeight());
	}
	*/
	@Override
	public void updateVisibility() {
		super.updateVisibility();

		visible = isVisible(this);
		// if (visible) draw();
	}
}
