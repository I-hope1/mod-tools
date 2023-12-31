package modtools.ui.menus;

import arc.func.Prov;
import arc.scene.style.Drawable;
import arc.util.pooling.Pools;

/**
 * The type Info list.
 */
public class InfoList extends MenuList {
	/**
	 * Withi info list.
	 *
	 * @param icon the icon
	 * @param name the name
	 * @return the info list
	 */
	public static InfoList withi(Drawable icon, Prov<String> name) {
		InfoList list = Pools.get(InfoList.class, InfoList::new, max).obtain();
		list.icon = icon;
		list.provider = name;
		list.cons = null;
		return list;
	}
}
