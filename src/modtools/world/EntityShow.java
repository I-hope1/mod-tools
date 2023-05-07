package modtools.world;

import arc.*;
import arc.scene.event.Touchable;
import arc.scene.ui.layout.*;
import arc.struct.*;
import mindustry.Vars;
import mindustry.ctype.UnlockableContent;
import mindustry.game.EventType.*;
import mindustry.gen.Groups;
import mindustry.graphics.Pal;
import mindustry.type.UnitType;
import mindustry.ui.*;

public class EntityShow {
	public static final int cols = 10;

	static Table cont,
			cinfo = (Table) Core.scene.find("coreinfo");

	public static void main() {
		cont = new Table(Styles.black5);
		cont.touchable = Touchable.disabled;
		Core.scene.add(cont);
		Events.run(Trigger.update, () -> {
			if (Vars.state.isMenu()) cont.clearChildren();
		});
		Events.on(WorldLoadEvent.class, e -> rebuild());
		Events.on(TileChangeEvent.class, e -> rebuild());
		Events.on(UnitSpawnEvent.class, e -> {
			UnitType u     = e.unit.type;
			Stack    stack = cont.find("icon:" + u.name);
			idMap.put(u.id, idMap.get(u.id, () -> 0) + 1);
			if (stack == null) {
				((Cell<Table>) cont.getCells().get(0)).setElement(buildTable(unitTypes));
			} else {
				rebuild();
				// cont.getCell(stack).setElement(new ItemImage(u.fullIcon, idMap.get(u.id)));
			}
		});
		Events.on(UnitDestroyEvent.class, e -> {
			UnitType  u     = e.unit.type;
			ItemImage stack = cont.find("icon:" + u.name);
			idMap.put(u.id, idMap.get(u.id, () -> 0) - 1);
			if (stack == null) {
				((Cell<Table>) cont.getCells().get(0)).setElement(buildTable(unitTypes));
			} else {
				rebuild();
				// cont.getCell(stack).setElement(new ItemImage(u.fullIcon, idMap.get(u.id)));
			}
		});
	}

	static IntMap<Integer> idMap = new IntMap<>();

	static Seq<UnlockableContent>
			unitTypes = Vars.content.units().as(),
			blocks    = Vars.content.blocks().as();

	public static void rebuild() {
		cont.y = cinfo.getPrefHeight();
		cont.x = Core.graphics.getWidth() / 2f;
		idMap.clear();
		Groups.unit.each(u -> {
			idMap.put(u.type.id, idMap.get(u.type.id, () -> 0) + 1);
		});
		Groups.build.each(building -> {
			idMap.put(building.block.id, idMap.get(building.block.id, () -> 0) + 1);
		});

		cont.clearChildren();
		cont.add(buildTable(unitTypes)).growX().row();
		cont.image().color(Pal.accent).growX();
		cont.add(buildTable(blocks)).growX();
	}

	public static Table buildTable(Seq<UnlockableContent> contents) {
		Table table = new Table();
		table.left().defaults().left();
		int c = 0;
		for (var u : contents) {
			if (idMap.containsKey(u.id)) {
				table.add(new ItemImage(u.fullIcon, idMap.get(u.id))).name("icon:" + u.name);
				if (++c % cols == 0) table.row();
			}
		}

		return table;
	}
}
