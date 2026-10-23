package modtools.ui.comp.limit;

import arc.func.Cons;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.ImageButton.ImageButtonStyle;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.*;
import arc.util.Log;

import static modtools.ui.comp.limit.Limit.isVisible;


public class LimitTable extends Table implements Limit {
	public LimitTable() { }
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
		// 直接内联 try/catch：本方法每帧对每个实例都会执行，
		// 交给 Tools.runLoggedException 会多出一层闭包捕获
		try {
			super.act(delta);
		} catch (Throwable e) {
			Log.err(e);
		}
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

	public Cell<Label> add(CharSequence text) {
		return add(new LimitLabel(text));
	}

	public void updateVisibility() {
		super.updateVisibility();
		/* visibility 供应器判 false 时不能被视口裁剪「救」回来 */
		if (visibility == null || visible) visible = isVisible(this);
	}
}