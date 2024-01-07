package modtools.ui.menu;

import arc.scene.style.Drawable;
import arc.util.pooling.Pools;

/**
 * The type Checkbox list.
 */
public class CheckboxList extends MenuList {
	/**
	 * The Checked.
	 */
	public boolean checked;
	/**
	 * Withc checkbox list.
	 *
	 * @param icon the icon
	 * @param name the name
	 * @param checked the checked
	 * @param run the run
	 * @return the checkbox list
	 */
	public static CheckboxList withc(Drawable icon, String name, boolean checked, Runnable run) {
		CheckboxList list = Pools.get(CheckboxList.class,CheckboxList::new, max).obtain();
		list.icon = icon;
		list.name = name;
		list.checked = checked;
		// Log.info("0) check: @, list.checked: @", checked, list.checked);
		list.cons = __ -> run.run();
		return list;
	}
}
