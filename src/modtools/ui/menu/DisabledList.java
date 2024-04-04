package modtools.ui.menu;

import arc.func.Boolp;
import arc.scene.style.Drawable;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.*;
import arc.util.pooling.Pools;

/**
 * The type Disabled list.
 */
public class DisabledList extends MenuItem {
	/**
	 * The Disabled.
	 */
	public Boolp disabled;
	/**
	 * Withd disabled list.
	 *
	 * @param icon the icon
	 * @param name the name
	 * @param disabled the disabled
	 * @param run the run
	 * @return the disabled list
	 */
	public static DisabledList withd(String key, Drawable icon, String name, Boolp disabled, Runnable run) {
		DisabledList list = Pools.get(DisabledList.class,DisabledList::new, max).obtain();
		list.key = key;
		list.icon = icon;
		list.name = name;
		list.disabled = disabled;
		// Log.info("0) check: @, list.checked: @", checked, list.checked);
		list.cons = _ -> run.run();
		return list;
	}

	public void build(Table p, Cell<TextButton> cell, Runnable hide) {
		super.build(p, cell, hide);
		cell.disabled(_ -> disabled.get()).row();
	}
}
