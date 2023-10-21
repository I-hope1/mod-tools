package modtools.ui.components.buttons;

import arc.input.KeyCode;
import arc.scene.Element;
import arc.scene.actions.Actions;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.Align;
import mindustry.gen.Icon;
import modtools.ui.HopeStyles;


public class FoldedImageButton extends ImageButton {
	private static final float duration = 0.1f;
	public FoldedImageButton(boolean autoFire) {
		this(autoFire, HopeStyles.clearNonei);
	}
	public FoldedImageButton(boolean autoFire, ImageButtonStyle style) {
		super(Icon.rightOpen, style);

		clicked(() -> {
			checked = !checked;
			if (autoFire) {
				change(getImage());
			}
		});
		update(() -> {
			if (autoFire) return;
			setOrigin(Align.center);
			change(getImage());
		});
		addListener(new InputListener() {
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				event.stop();
				return super.touchDown(event, x, y, pointer, button);
			}
			public void enter(InputEvent event, float x, float y, int pointer, Element fromActor) {
				event.stop();
			}
		});
	}
	public void fireCheck(boolean checked) {
		this.checked = checked;
		getImage().rotation = checked ? -90 : 0;
	}
	private void change(Image image) {
		if (checked) {
			if (rebuild != null) rebuild.run();
			if (image.rotation != -90) image.actions(Actions.rotateTo(-90, duration));
			cell.setElement(table);
		} else {
			if (image.rotation != 0) image.actions(Actions.rotateTo(0, duration));
			cell.clearElement();
		}
	}
	boolean checked;
	public Table    table;
	public Runnable rebuild;
	public Cell<?>  cell;


}
