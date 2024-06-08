package modtools.ui.components;

import arc.scene.event.Touchable;
import arc.struct.Seq;
import modtools.ui.IntUI.IMenu;

public class Hitter extends FillElement implements IMenu {
	private static final Seq<Hitter> all = new Seq<>();
	public Hitter() {
		all.add(this);
	}
	public Hitter(Runnable clicked) {
		this();
		this.clicked(() -> {
			clicked.run();
			remove();
		});
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
