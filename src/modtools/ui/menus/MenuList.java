package modtools.ui.menus;

import arc.func.*;
import arc.scene.style.Drawable;
import arc.scene.ui.Button;
import arc.util.pooling.Pools;

import static modtools.ui.Contents.tester;

/**
 * The type Menu list.
 */
/* -----List------- */
public class MenuList {
	/**
	 * The Max.
	 */
	public static int          max = 20;
	/**
	 * The Icon.
	 */
	public        Drawable     icon;
	/**
	 * The Name.
	 */
	public        String       name;
	/**
	 * The Provider.
	 */
	public        Prov<String> provider;
	/**
	 * The Cons.
	 */
	public        Cons<Button> cons;
	/**
	 * With menu list.
	 *
	 * @param icon the icon
	 * @param name the name
	 * @param run the run
	 * @return the menu list
	 */
	public static MenuList with(Drawable icon, String name, Runnable run) {
		MenuList list = Pools.get(MenuList.class, MenuList::new, max).obtain();
		list.icon = icon;
		list.name = name;
		list.provider = null;
		list.cons = __ -> run.run();
		return list;
	}
	/**
	 * With menu list.
	 *
	 * @param icon the icon
	 * @param name the name
	 * @param cons the cons
	 * @return the menu list
	 */
	public static MenuList with(Drawable icon, String name, Cons<Button> cons) {
		MenuList list = Pools.get(MenuList.class, MenuList::new, max).obtain();
		list.icon = icon;
		list.name = name;
		list.provider = null;
		list.cons = cons;
		return list;
	}
	/**
	 * With menu list.
	 *
	 * @param icon the icon
	 * @param provider the provider
	 * @param run the run
	 * @return the menu list
	 */
	public static MenuList with(Drawable icon, Prov<String> provider, Runnable run) {
		MenuList list = Pools.get(MenuList.class, MenuList::new, max).obtain();
		list.icon = icon;
		list.name = null;
		list.provider = provider;
		list.cons = __ -> run.run();
		return list;
	}
	/**
	 * With menu list.
	 *
	 * @param icon the icon
	 * @param name the name
	 * @param prov the prov
	 * @return the menu list
	 */
	public static MenuList with(Drawable icon, String name, Prov prov) {
		return with(icon, name, () -> {
			tester.put(prov.get());
		});
	}

	MenuList() {}

	/**
	 * Gets name.
	 *
	 * @return the name
	 */
	public String getName() {
		return provider != null ? provider.get() : name;
	}
}
