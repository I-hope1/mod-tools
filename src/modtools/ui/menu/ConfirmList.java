package modtools.ui.menu;

import arc.scene.style.Drawable;
import modtools.ui.IntUI;

/**
 * The type Confirm list.
 */
public class ConfirmList extends MenuItem {
	public static MenuItem with(String key, Drawable icon, String name, String text, Runnable run) {
		MenuItem list = with(key, icon, name, run);
		list.cons = _ -> IntUI.showConfirm(text, run);
		return list;
	}
}
