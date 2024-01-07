package modtools.ui.menu;

import arc.scene.style.Drawable;
import modtools.ui.IntUI;

/**
 * The type Confirm list.
 */
public class ConfirmList extends MenuList {
	/**
	 * With menu list.
	 *
	 * @param icon the icon
	 * @param name the name
	 * @param text the text
	 * @param run the run
	 * @return the menu list
	 */
	public static MenuList with(Drawable icon, String name, String text, Runnable run) {
		MenuList list = with(icon, name, run);
		list.cons = __ -> IntUI.showConfirm(text, run);
		return list;
	}
}
