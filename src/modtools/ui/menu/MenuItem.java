package modtools.ui.menu;

import arc.func.*;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.*;
import arc.util.Nullable;
import arc.util.pooling.Pools;
import modtools.ui.HopeStyles;
import modtools.utils.Tools;
import modtools.utils.ui.CellTools;

import static modtools.ui.Contents.tester;

/**
 * The type Menu list.
 */
public class MenuItem {
	public static int max = 24;

	/** Key for Settings */
	public String   key;
	/** The Icon of button */
	public Drawable icon;
	/** The button text Used for display */
	public String   name;
	/**
	 * The name provider. (Nullable)
	 */
	public @Nullable
	Prov<String> provider;
	/** The cons will be call when clicked. */
	public Cons<Button> cons;
	public static MenuItem with(String key, Drawable icon, String name, Runnable run) {
		MenuItem list = Pools.get(MenuItem.class, MenuItem::new, max).obtain();
		list.key = key;
		list.icon = icon;
		list.name = name;
		list.provider = null;
		list.cons = _ -> run.run();
		return list;
	}
	public static MenuItem with(String key, Drawable icon, String name, Cons<Button> cons) {
		MenuItem list = Pools.get(MenuItem.class, MenuItem::new, max).obtain();
		list.key = key;
		list.icon = icon;
		list.name = name;
		list.provider = null;
		list.cons = cons;
		return list;
	}
	public static MenuItem with(String key, Drawable icon, Prov<String> provider, Runnable run) {
		MenuItem list = Pools.get(MenuItem.class, MenuItem::new, max).obtain();
		list.key = key;
		list.icon = icon;
		list.name = null;
		list.provider = provider;
		list.cons = _ -> run.run();
		return list;
	}
	public static MenuItem with(String key, Drawable icon, String name, Prov<?> prov) {
		return with(key, icon, name, () -> {
			tester.quietPut(prov.get());
		});
	}

	MenuItem() { }

	public String getName() {
		return provider != null ? provider.get() : name;
	}

	/** The style of Button  */
	public TextButtonStyle style() {
		return HopeStyles.flatt;
	}

	/** @see Cell#unset */
	public float iconSize() {
		return icon != null ? 24 : CellTools.unset;/* unset */
	}
	public void build(Table p, Cell<TextButton> cell, Runnable hide) {
		cell.with(b -> b.clicked(Tools.runT(() -> {
			hide.run();
			if (cons != null) cons.get(b);
		}))).checked(this instanceof CheckboxList l && l.checked);
	}
}
