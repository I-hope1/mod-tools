package modtools.editor;

import arc.struct.Seq;
import modtools.ui.menu.MenuItem;

public interface HView {
	Seq<MenuItem> menus(HItem item);

	boolean active();

	void addItem(HItem hItem);
}
