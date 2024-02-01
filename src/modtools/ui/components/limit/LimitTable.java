package modtools.ui.components.limit;

import arc.func.Cons;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.ImageButton.ImageButtonStyle;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.*;
import modtools.utils.Tools;

import java.util.*;

import static modtools.ui.components.limit.Limit.isVisible;


public class LimitTable extends Table implements Limit {
	private HashSet<Element> limitElems;
	public LimitTable() {}
	public LimitTable(Drawable background) {
		super(background);
	}
	public LimitTable(Drawable background, Cons<Table> cons) {
		super(background, cons);
	}
	public LimitTable(Cons<Table> cons) {
		super(cons);
	}
	public void act(float delta) {
		Tools.runLoggedException(() -> super.act(delta));
	}

	/* @Override
	public Cell<Label> add(CharSequence text) {
		return add(new LimitLabel(text));
	}

	@Override
	public Cell<Label> add(CharSequence text, LabelStyle labelStyle) {
		return add(new LimitLabel(text, labelStyle));
	} */

	public Cell<Table> table(Drawable background) {
		Table table = new LimitTable(background);
		return add(table);
	}

	public Cell<Table> table(Drawable background, int align, Cons<Table> cons) {
		Table table = new Table(background);
		table.align(align);
		cons.get(table);
		return add(table);
	}

	public Cell<Table> table(Cons<Table> cons) {
		Table table = new LimitTable();
		cons.get(table);
		return add(table);
	}

	public Cell<TextButton> button(String text, TextButtonStyle style, Runnable listener) {
		TextButton button = new LimitTextButton(text, style);
		if (listener != null)
			button.changed(listener);
		return add(button);
	}
	public Cell<Button> button(Cons<Button> cons, Runnable listener) {
		return super.button(cons, listener);
	}

	public Cell<ImageButton> button(Drawable icon, ImageButtonStyle style, Runnable listener) {
		ImageButton button = new LimitImageButton(icon, style);
		button.clicked(listener);
		button.resizeImage(icon.imageSize());
		return add(button);
	}
	public Cell<ImageButton> button(Drawable icon, ImageButtonStyle style, float isize, Runnable listener) {
		ImageButton button = new LimitImageButton(icon, style);
		button.clicked(listener);
		button.resizeImage(isize);
		return add(button);
	}
	public Cell<Image> image() {
		return add(new LimitImage());
	}
	public Cell<Image> image(Drawable name) {
		return add(new LimitImage(name));
	}

	HashSet<Element> acquireLimitElems() {
		return limitElems == null ? limitElems = new HashSet<>() : limitElems;
	}
	public <T extends Element> Cell<T> add(T element) {
		if (!(element instanceof Limit) && element != null) acquireLimitElems().add(element);
		return super.add(element);
	}
	// final Vec2 absPos = new Vec2();

	public void updateVisibility() {
		// Limit.absPos(this, absPos);

		visible = isVisible(this);
		if (visible) acquireLimitElems().forEach(el -> el.visible = isVisible(el));
	}
}
