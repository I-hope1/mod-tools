package modtools.ui.menu;

import arc.Core;
import arc.func.Prov;
import arc.scene.style.Drawable;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.*;
import arc.struct.Seq;
import arc.util.pooling.Pool.Poolable;
import arc.util.pooling.Pools;
import modtools.ui.IntUI;
import modtools.utils.ui.search.BindCell;

import static modtools.ui.IntUI.*;

/**
 * The type Folded list.
 */
public class FoldedList extends MenuList implements Poolable {
	/**
	 * The Children getter.
	 */
	Prov<Seq<MenuList>> childrenGetter;
	/**
	 * The Children.
	 */
	Seq<MenuList>       children;
	/**
	 * Withf folded list.
	 *
	 * @param icon the icon
	 * @param name the name
	 * @param children the children
	 * @return the folded list
	 */
	public static FoldedList withf(Drawable icon, String name, Prov<Seq<MenuList>> children) {
		FoldedList list = Pools.get(FoldedList.class,FoldedList::new, max).obtain();
		list.icon = icon;
		list.name = name;
		list.children = null;
		list.childrenGetter = children;
		// Log.info("0) check: @, list.checked: @", checked, list.checked);
		list.cons = null;
		return list;
	}
	// /**
	//  * Gets children.
	//  *
	//  * @return the children
	//  */
	// public Seq<MenuList> getChildren() {
	// 	if (children == null) children = childrenGetter.get();
	// 	return children;
	// }
	public void reset() {
		if (children != null) Pools.freeAll(children, false);
	}

	public void build(Table p, Cell<TextButton> cell, Runnable hide) {
		Core.app.post(() -> {
			class MyRun implements Runnable {
				Cell<Table> newCell;
				BindCell    bcell;
				public void run() {
					if (newCell == null) {
						Seq<MenuList> list = childrenGetter.get();
						newCell = IntUI.showMenuList(list,freeAllMenu(list), p, hide);
						bcell = new BindCell(newCell);
					} else bcell.toggle();
				}
			}
			cell.get().clicked(new MyRun());
		});
	}
}
