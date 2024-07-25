package modtools.ui.menu;

import arc.func.*;
import arc.scene.style.Drawable;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.*;
import arc.util.pooling.Pools;
import modtools.ui.HopeStyles;

/**
 * The type Checkbox list.
 */
public class CheckboxList extends MenuItem {
	public Boolp checked;

	public static CheckboxList withc(String key, Drawable icon, String name, Boolp checked, Runnable run) {
		CheckboxList list = Pools.get(CheckboxList.class, CheckboxList::new, max).obtain();
		list.key = key;
		list.icon = icon;
		list.name = name;
		list.checked = checked;
		// Log.info("0) check: @, list.checked: @", checked, list.checked);
		list.cons = _ -> run.run();
		return list;
	}
	public void build(Table p, Cell<TextButton> cell, Runnable hide) {
		super.build(p, cell, hide);
		cell.update(b -> b.setChecked(checked == null || checked.get()));
	}
	public void reset() {
		super.reset();
		checked = null;
	}
	public TextButtonStyle style() {
		return HopeStyles.flatTogglet;
	}
}
