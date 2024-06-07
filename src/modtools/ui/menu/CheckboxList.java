package modtools.ui.menu;

import arc.scene.style.Drawable;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.util.pooling.Pools;
import modtools.ui.HopeStyles;

/**
 * The type Checkbox list.
 */
public class CheckboxList extends MenuItem {
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
	public static CheckboxList withc(String key, Drawable icon, String name, boolean checked, Runnable run) {
		CheckboxList list = Pools.get(CheckboxList.class,CheckboxList::new, max).obtain();
		list.key = key;
		list.icon = icon;
		list.name = name;
		list.checked = checked;
		// Log.info("0) check: @, list.checked: @", checked, list.checked);
		list.cons = _ -> run.run();
		return list;
	}

	public TextButtonStyle style() {
		return HopeStyles.flatTogglet;
	}
}
