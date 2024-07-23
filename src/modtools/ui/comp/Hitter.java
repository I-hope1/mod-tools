package modtools.ui.comp;

import arc.scene.Element;
import arc.scene.event.Touchable;
import arc.struct.Seq;
import modtools.ui.IntUI.IMenu;
import modtools.ui.control.HopeInput;

/** 用于判断鼠标事件，然后隐藏 */
public class Hitter extends FillElement implements IMenu {
	private static final Seq<Hitter> all = new Seq<>();

	/**
	 * 是否自动隐藏
	 * <p>PS:{@code true}时不可触摸</p>
	 * <div>{@code false}时可触摸</div>
	 **/
	public boolean autoClose;
	// public int id;
	public Hitter() {
		all.add(this);
		// id = all.size;
		autoClose = false;
	}
	public Hitter(Runnable clicked) {
		this();
		clicked(clicked);
		autoClose = true;
	}
	public boolean canHide() {
		return this == Hitter.peek() || this.getZIndex() >= Hitter.peek().getZIndex();
	}
	/** @return true if the hitter hide successfully. */
	public boolean hide() {
		boolean b = canHide();
		if (b) fireClick();
		return b;
	}
	public Element hit(float x, float y, boolean touchable) {
		if (!autoClose) return super.hit(x, y, touchable);

		if (HopeInput.mouseDown()
		    && !HopeInput.mouseDragged()
		    && super.hit(x, y, touchable) == this
		    && hide()) {
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

	/* public String toString() {
		return "Hitter" + id;
	} */
}
