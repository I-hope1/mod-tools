package modtools.ui.content.world;

import arc.graphics.Color;
import arc.scene.Element;
import arc.scene.ui.layout.Table;
import arc.util.Align;
import mindustry.world.Tile;
import modtools.ui.IntUI;

public class WorldInfo {
	public static void showInfo(Element element, Object o) {
		IntUI.showSelectTable(element, (p, hide, cont) -> {
			if (o instanceof Tile) build(p, (Tile) o);
			else p.add("TODO");
		}, false, Align.center);
	}
	public static void build(Table p, Tile tile) {
		p.add("block").color(Color.lightGray);
		p.add(tile.block().name);
		p.row().add("floor").color(Color.lightGray);
		p.add(tile.floor().name);
	}
}
