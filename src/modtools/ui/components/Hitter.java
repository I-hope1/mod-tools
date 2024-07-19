package modtools.ui.components;

import arc.scene.Element;
import arc.scene.event.Touchable;
import arc.struct.Seq;
import modtools.ui.IntUI.IMenu;
import modtools.ui.control.HopeInput;

public class Hitter extends FillElement implements IMenu {
	private static final Seq<Hitter> all = new Seq<>();
	public boolean autoClose;
	public Hitter() {
		all.add(this);
		autoClose = false;
	}
	public Hitter(Runnable clicked) {
		this();
		clicked(clicked);
		autoClose = true;
	}
	public Element hit(float x, float y, boolean touchable) {
		if (autoClose && HopeInput.mouseDown()
		    && super.hit(x, y, touchable) == this) {
			fireClick();
			remove();
		}

		return null;
	}
	public boolean remove() {
		boolean b = super.remove();
		return b && all.remove(this, true);
	}

	public static Hitter firstTouchable() {
		return all.find(h -> h.touchable == Touchable.enabled);
	}
	public static boolean any() {
		return firstTouchable() != null;
	}
	public static Hitter peek() {
		return all.peek();
	}
	public static boolean contains(Hitter hitter) {
		return all.contains(hitter);
	}
}
