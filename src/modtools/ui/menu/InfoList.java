package modtools.ui.menu;

import arc.func.Prov;
import arc.graphics.Color;
import arc.scene.style.Drawable;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.*;
import arc.util.pooling.Pools;

/**
 * The type Info list.
 */
public class InfoList extends MenuItem {
	private Color color = Color.white;
	/**
	 * Get a instance of InfoList.
	 */
	public static InfoList withi(String key, Drawable icon, Prov<String> name) {
		InfoList list = Pools.get(InfoList.class, InfoList::new, max).obtain();
		list.key = key;
		list.icon = icon;
		list.provider = name;
		list.cons = null;
		return list;
	}
	public void reset() {
		super.reset();
		color = Color.white;
	}
	public void build(Table p, Cell<TextButton> cell, Runnable hide) {
		cell.get().getLabel().setColor(color);
	}
	public MenuItem color(Color color) {
		this.color = color;
		return this;
	}
}
