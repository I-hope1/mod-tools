package modtools.ui.components;

import arc.struct.Seq;
import modtools.ui.IntUI.IMenu;

public class Hitter extends FillElement implements IMenu {
	public static Seq<Hitter> all = new Seq<>();
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
		if (b) all.remove(this, true);
		return b;
	}
}
