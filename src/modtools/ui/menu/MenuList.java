package modtools.ui.menu;

import arc.func.*;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.*;
import arc.util.Nullable;
import arc.util.pooling.Pools;
import modtools.ui.HopeStyles;

import static modtools.ui.Contents.tester;
import static modtools.utils.Tools.catchRun;

/**
 * The type Menu list.
 */
public class MenuList {
	/**
	 * The Max.
	 */
	public static    int          max = 20;
	/**
	 * The Icon.
	 */
	public           Drawable     icon;
	/**
	 * The Name.
	 */
	public           String       name;
	/**
	 * The name provider. (Nullable)
	 */
	public @Nullable Prov<String> provider;
	/**
	 * The cons will be call when clicked.
	 */
	public           Cons<Button> cons;
	/**
	 * With menu list.
	 * @param icon the icon
	 * @param name the name
	 * @param run  the run
	 * @return the menu list
	 */
	public static MenuList with(Drawable icon, String name, Runnable run) {
		MenuList list = Pools.get(MenuList.class, MenuList::new, max).obtain();
		list.icon = icon;
		list.name = name;
		list.provider = null;
		list.cons = _ -> run.run();
		return list;
	}
	/**
	 * With menu list.
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
	 * @param icon     the icon
	 * @param provider the provider
	 * @param run      the run
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
	 * @param icon the icon
	 * @param name the name
	 * @param prov the prov
	 * @return the menu list
	 */
	public static MenuList with(Drawable icon, String name, Prov<?> prov) {
		return with(icon, name, () -> {
			tester.quietPut(prov.get());
		});
	}

	MenuList() { }

	/**
	 * Gets name.
	 * @return the name
	 */
	public String getName() {
		return provider != null ? provider.get() : name;
	}


	public TextButtonStyle style() {
		return this instanceof CheckboxList || this instanceof FoldedList
		 ? HopeStyles.flatToggleMenut : HopeStyles.flatt;
	}
	/** @see Cell#unset */
	public float iconSize() {
		return icon != null ? 24 : Float.NEGATIVE_INFINITY;/* unset */
	}
	public void build(Table p, Cell<TextButton> cell, Runnable hide) {
		cell.with(b -> b.clicked(catchRun(() -> {
			if (cons != null) cons.get(b);
			hide.run();
		}))).checked(this instanceof CheckboxList l && l.checked);
	}
}
