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
	public FoldedImageButton(boolean fireOnlyClick) {
		this(fireOnlyClick, HopeStyles.clearNonei);
	}
	public FoldedImageButton(boolean fireOnlyClick, ImageButtonStyle style) {
		super(Icon.rightOpen, style);

		clicked(() -> {
			checked = !checked;
			if (fireOnlyClick) {
				onChange();
			}
		});
		update(() -> {
			if (fireOnlyClick) return;
			setOrigin(Align.center);
			onChange();
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
		fireCheck(checked, true);
	}

	public void fireCheck(boolean checked, boolean fireEvents) {
		this.checked = checked;
		if (fireEvents) onChange();
		else getImage().rotation = checked ? -90 : 0;
	}
	private void onChange() {
		Image image = getImage();
		if (checked) {
			if (rebuild != null) rebuild.run();
			if (image.rotation != -90) image.actions(Actions.rotateTo(-90, duration));
			if (cell != null) cell.setElement(element);
		} else {
			if (_clear != null) _clear.run();
			if (image.rotation != 0) image.actions(Actions.rotateTo(0, duration));
			if (cell != null) cell.clearElement();
		}
	}
	boolean checked;
	public void setContainer(Cell<?> cell) {
		this.cell = cell;
		this.element = cell.get();
	}
	public Element  element;
	public Runnable rebuild, _clear;
	public Cell<?>  cell;


}
