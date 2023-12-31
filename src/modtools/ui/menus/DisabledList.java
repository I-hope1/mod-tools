package modtools.ui.menus;

import arc.func.Boolp;
import arc.scene.style.Drawable;
import arc.util.pooling.Pools;

/**
 * The type Disabled list.
 */
public class DisabledList extends MenuList {
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
	public static DisabledList withd(Drawable icon, String name, Boolp disabled, Runnable run) {
		DisabledList list = Pools.get(DisabledList.class,DisabledList::new, max).obtain();
		list.icon = icon;
		list.name = name;
		list.disabled = disabled;
		// Log.info("0) check: @, list.checked: @", checked, list.checked);
		list.cons = __ -> run.run();
		return list;
	}
}
