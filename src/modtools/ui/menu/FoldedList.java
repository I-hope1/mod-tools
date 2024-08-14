package modtools.ui.menu;

import arc.func.Prov;
import arc.scene.style.Drawable;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.*;
import arc.struct.Seq;
import arc.util.*;
import arc.util.pooling.Pool.Poolable;
import arc.util.pooling.Pools;
import modtools.ui.*;
import modtools.ui.IntUI.SelectTable;
import modtools.utils.search.BindCell;

/**
 * The type Folded list.
 */
public class FoldedList extends MenuItem implements Poolable {
	/**
	 * The Children getter.
	 */
	Prov<Seq<MenuItem>> childrenGetter;
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
		list.childrenGetter = children;
		// Log.info("0) check: @, list.checked: @", checked, list.checked);
		list.cons = null;
		return list;
	}
	@Override
	public TextButtonStyle style() {
		return HopeStyles.flatTogglet;
	}
	@Override
	public void reset() {
		super.reset();
		if (bcell != null) bcell.clear();
	}

	BindCell bcell;
	@Override
	public Cell<?> build(Table p, Runnable hide) {
		var cell = newMenuItemButton(p);
		TextButton button = cell.get();
		button.clicked(() -> {
			if (!button.isChecked()) return;
			SelectTable table = MenuBuilder.showMenuListDispose(childrenGetter);
			if (table == null) return;
			table.hidden(() -> button.setChecked(false));
			table.update(() -> {
				IntUI.positionTooltip(button, Align.topRight, table, Align.topLeft);
				/* int align = Align.topRight;
				button.localToStageCoordinates(Tmp.v1.set(button.getX(align) - button.x, button.getY(align) - button.y));
				table.setPosition(Tmp.v1.x, Tmp.v1.y, Align.topLeft);
				if (table.y < 0) {
					align = Align.bottomRight;
					button.localToStageCoordinates(Tmp.v1.set(button.getX(align) - button.x, button.getY(align) - button.y));
					table.setPosition(Tmp.v1.x, Tmp.v1.y, Align.bottomLeft);
				} */
				IntUI.checkBound(table);
			});
		});
		return cell;
	}
}
