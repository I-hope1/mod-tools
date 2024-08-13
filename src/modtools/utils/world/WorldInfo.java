package modtools.utils.world;

import arc.Core;
import arc.graphics.Color;
import arc.scene.Element;
import arc.scene.ui.layout.Table;
import arc.util.Align;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.Bar;
import mindustry.world.Tile;
import modtools.ui.IntUI;
import modtools.utils.*;

public class WorldInfo {
	public static void showInfo(Element element, Object o) {
		IntUI.showSelectTable(element, (p, _, _) -> SR.apply(() ->
			SR.of(o)
			 .isInstance(Tile.class, p, WorldInfo::build)
			 .isInstance(Building.class, p,WorldInfo::build)
			 .isInstance(Unit.class, p,WorldInfo::build)
			 .isInstance(Object.class, x -> p.add("TODO")))
		 , false, Align.center);
	}
	public static void build(Tile tile, Table p) {
		p.left().defaults().left();
		p.add("block").color(Color.lightGray);
		p.image(tile.block().fullIcon).size(24);
		p.add(tile.block().name);
		p.row().add("floor").color(Color.lightGray);
		p.image(tile.floor().fullIcon).size(24);
		p.add(tile.floor().name);
		if (tile.drop() != null) {
			p.row().add("ore").color(Color.lightGray);
			p.image(tile.drop().fullIcon).size(24);
			p.add(tile.drop().name);
		}
	}
	public static void build(Building build, Table p) {
		p.left().defaults().left();
		// build的所有items，build.items
		if (build.items != null) {
			p.add("ITEMS").color(Pal.accent).row();
			p.table(it -> {
				it.left().defaults().left();
				build.items.each((item, amount) -> {
					it.image(item.uiIcon).padRight(6f);
					it.add(item.localizedName).growX().left().padRight(6f);
					it.add("" + amount);
					it.row();
				});
			}).row();
		}
		// build的所有liquids，build.liquids
		if (build.liquids != null) {
			p.add("LIQUIDS").color(Pal.accent).row();
			p.table(li -> {
				li.left().defaults().left();
				li.left().defaults().left();
				build.liquids.each((liquid, amount) -> {
					li.image(liquid.uiIcon).padRight(6f);
					li.add(liquid.localizedName).growX().left().padRight(6f);
					li.add("" + amount);
					li.row();
				});
			}).row();
		}
		p.row();
		// 显示build的血量
		// p.add(new Bar(() -> Core.bundle.format("@stat.health", build.health), () -> Pal.health, () -> build.health / build.maxHealth)).growX();
	}
	public static void build(Unit unit, Table p) {
		// 显示unit的血量
		p.add(new Bar(() -> Core.bundle.format("@stat.health", unit.health), () -> Pal.health, () -> unit.health / unit.maxHealth));
	}
}
