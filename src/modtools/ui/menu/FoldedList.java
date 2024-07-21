package modtools.ui.menu;

import arc.Core;
import arc.func.Prov;
import arc.scene.style.Drawable;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.*;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.pooling.Pool.Poolable;
import arc.util.pooling.Pools;
import modtools.ui.*;
import modtools.ui.comp.TransformTable;
import modtools.utils.ElementUtils;
import modtools.utils.ui.search.BindCell;

import static modtools.ui.menu.MenuBuilder.freeAllMenu;

/**
 * The type Folded list.
 */
public class FoldedList extends MenuItem implements Poolable {
	/**
	 * The Children getter.
	 */
	Prov<Seq<MenuItem>> childrenGetter;
	/**
	 * The Children.
	 */
	Seq<MenuItem>       children;
	/**
	 * Withf folded list.
	 * @param icon     the icon
	 * @param name     the name
	 * @param children the children
	 * @return the folded list
	 */
	public static FoldedList withf(String key, Drawable icon, String name, Prov<Seq<MenuItem>> children) {
		FoldedList list = Pools.get(FoldedList.class, FoldedList::new, max).obtain();
		list.key = key;
		list.icon = icon;
		list.name = name;
		list.children = null;
		list.childrenGetter = children;
		// Log.info("0) check: @, list.checked: @", checked, list.checked);
		list.cons = null;
		return list;
	}
	public TextButtonStyle style() {
		return HopeStyles.flatTogglet;
	}
	public void reset() {
		if (children != null) Pools.freeAll(children, false);
	}

	public void build(Table p, Cell<TextButton> cell, Runnable hide) {
		Core.app.post(() -> {
			class MyRun implements Runnable {
				BindCell bcell;
				public void run() {
					if (bcell == null) {
						Seq<MenuItem> list = childrenGetter.get();
						TextButton    target = cell.get();
						var newCell = MenuBuilder.showMenuList(list,
						 freeAllMenu(list),
						 new TransformTable(target, ElementUtils.findClosestPane(target), Align.topRight).top().left(),
						 hide);
						bcell = new BindCell(newCell);
					} else bcell.toggle();
				}
			}
			cell.get().clicked(new MyRun());
		});
	}
}
