package modtools.ui.components.limit;

import arc.func.Cons;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.*;

import static modtools.ui.components.limit.Limit.isVisible;


public class LimitTable extends Table {
	public LimitTable() {
	}
	public LimitTable(Drawable background) {
		super(background);
	}
	public LimitTable(Drawable background, Cons<Table> cons) {
		super(background, cons);
	}
	public LimitTable(Cons<Table> cons) {
		super(cons);
	}

	@Override
	public Cell<Label> add(CharSequence text) {
		return add(new LimitLabel(text));
	}

	@Override
	public Cell<Label> add(CharSequence text, LabelStyle labelStyle) {
		return add(new LimitLabel(text, labelStyle));
	}

	@Override
	public Cell<TextButton> button(String text, TextButtonStyle style, Runnable listener) {
		TextButton button = new LimitTextButton(text, style);
		if (listener != null)
			button.changed(listener);
		return add(button);
	}

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

	@Override
	public Cell<Button> button(Cons<Button> cons, Runnable listener) {
		return super.button(cons, listener);
	}

	@Override
	public Cell<Image> image(Drawable name) {
		return add(new LimitImage(name));
	}

	@Override
	public void updateVisibility() {
		visible = isVisible(this);
	}
}
