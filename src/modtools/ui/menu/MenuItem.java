package modtools.ui.menu;

import arc.Core;
import arc.func.*;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.*;
import arc.util.*;
import arc.util.pooling.Pool.Poolable;
import arc.util.pooling.Pools;
import modtools.IntVars;
import modtools.content.debug.Tester;
import modtools.ui.*;
import modtools.ui.IntUI.ITooltip;
import modtools.utils.Tools;
import modtools.utils.ui.CellTools;

/**
 * The type Menu list.
 */
public class MenuItem implements Poolable {
	public static int max = 24;

	/** Key for Settings */
	public String   key;
	/** The Icon of button */
	public Drawable icon;
	/** The button text used for display */
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
			Tester.quietPut(prov.get());
		});
	}

	MenuItem() { }

	public String getName() {
		return provider != null ? provider.get() : name;
	}

	/** The style of Button  */
	public TextButtonStyle style() {
		return HopeStyles.flatToggleMenut;
	}

	/** @see Cell#unset */
	public float iconSize() {
		return icon != null ? 24 : CellTools.unset;
	}
	public Cell<?> build(Table p, Runnable hide) {
		var cell = newMenuItemButton(p);
		// cell.get().getLabel().setFontScale(0.9f);
		TextButton b = cell.get();
		b.clicked(Tools.runT(() -> {
			Cons<Button> c = cons;
			hide.run();
			if (c != null) c.get(b);
		}));
		return cell;
	}
	@SuppressWarnings("StringTemplateMigration")
	Cell<TextButton> newMenuItemButton(Table p) {
		var cell = p.button(getName(), icon, style(),
			iconSize(), IntVars.EMPTY_RUN
		 ).minSize(Float.NEGATIVE_INFINITY, IntUI.FUNCTION_BUTTON_SIZE)
		 .growX().left()
		 .padTop(-1)
		 .marginLeft(5f).marginRight(5f)
		 .wrapLabel(false);
		cell.row();
		if (Core.bundle.has("menu." + key)) {
			cell.get().addListener(new ITooltip(() -> Core.bundle.get("menu." + key)));
		}
		cell.get().getLabelCell().padLeft(8f).labelAlign(Align.left);
		return cell;
	}
	public void reset() {
		key = null;
		icon = null;
		name = null;
		provider = null;
		cons = null;
	}
}
