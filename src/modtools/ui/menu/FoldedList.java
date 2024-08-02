package modtools.ui.menu;

import arc.Core;
import arc.func.Prov;
import arc.scene.style.Drawable;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.*;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.pooling.*;
import arc.util.pooling.Pool.Poolable;
import modtools.ui.HopeStyles;
import modtools.ui.comp.TransformTable;
import modtools.utils.ElementUtils;
import modtools.utils.ui.search.BindCell;

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
		super.reset();
		if (children != null) Pools.freeAll(children, false);
		if (bcell != null) bcell.clear();
	}

	BindCell bcell;
	public void build(Table p, Cell<TextButton> cell, Runnable hide) {
		Core.app.post(() -> {
			cell.get().clicked(() -> {
				if (bcell == null) {
						Seq<MenuItem> list = childrenGetter.get();
						TextButton    target = cell.get();
						var newCell = MenuBuilder.showMenuList(list,
						 MenuBuilder.freeAllMenu(list),
						 new TransformTable(target, ElementUtils.findClosestPane(target), Align.topRight).top().left(),
						 hide);
						bcell = BindCell.of(newCell);
					} else bcell.toggle();
			});
		});
	}
}
