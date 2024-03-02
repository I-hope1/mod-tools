package modtools.ui.components;

import modtools.struct.v6.HSeq;
import modtools.ui.IntUI.IMenu;

public class Hitter extends FillElement implements IMenu {
	public static HSeq<Hitter> all = new HSeq<>();
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
