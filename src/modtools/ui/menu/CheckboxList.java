package modtools.ui.menu;

import arc.func.*;
import arc.scene.style.Drawable;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.*;
import arc.util.pooling.Pools;
import modtools.ui.HopeStyles;

import static modtools.utils.Tools.as;

/**
 * The type Checkbox list.
 */
public class CheckboxList extends MenuItem {
	public Boolp checked;

	public static CheckboxList withc(String key, Drawable icon, String name, Boolp checked, Runnable run) {
		CheckboxList list = Pools.get(CheckboxList.class, CheckboxList::new, max).obtain();
		list.key = key;
		list.icon = icon;
		list.name = name;
		list.checked = checked;
		// Log.info("0) check: @, list.checked: @", checked, list.checked);
		list.cons = _ -> run.run();
		return list;
	}
	public Cell<?> build(Table p, Runnable hide) {
		Cell<TextButton> cell = as(super.build(p, hide));
		cell.update(b -> b.setChecked(checked == null || checked.get()));
		return cell;
	}
	public void reset() {
		super.reset();
		checked = null;
	}
	public TextButtonStyle style() {
		return HopeStyles.flatToggleMenut;
	}
}
