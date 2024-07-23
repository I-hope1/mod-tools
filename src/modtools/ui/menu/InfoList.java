package modtools.ui.menu;

import arc.func.Prov;
import arc.scene.style.Drawable;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.*;
import arc.util.pooling.Pools;

/**
 * The type Info list.
 */
public class InfoList extends MenuItem {
	/**
	 * Withi info list.
	 *
	 * @param icon the icon
	 * @param name the name
	 * @return the info list
	 */
	public static InfoList withi(String key, Drawable icon, Prov<String> name) {
		InfoList list = Pools.get(InfoList.class, InfoList::new, max).obtain();
		list.key = key;
		list.icon = icon;
		list.provider = name;
		list.cons = null;
		return list;
	}
	public void build(Table p, Cell<TextButton> cell, Runnable hide) {
	}
}
