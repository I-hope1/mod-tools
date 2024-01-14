package modtools.jsfunc;

import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.gen.*;
import mindustry.world.Tile;

import static modtools.ui.Contents.selection;

public interface WORLD {
	static void focusWorld(Tile obj) {selection.focusInternal.add(obj);}
	static void focusWorld(Building obj) {selection.focusInternal.add(obj);}
	static void focusWorld(Unit obj) {selection.focusInternal.add(obj);}
	static void focusWorld(Bullet obj) {selection.focusInternal.add(obj);}
	static void focusWorld(Seq<?> obj) {selection.focusInternal.add(obj);}
	static void removeFocusAll() {selection.focusInternal.clear();}

	interface UNIT {
		static void removeAllUnits() {
			Groups.unit.each(Unit::remove);
			Groups.unit.clear();
			// cont.check("服务器适配", b -> server = b);
		}
		static void killAllUnits() {
			Groups.unit.each(Unit::kill);
		}
		static void noScorchMarks() {
			Vars.content.units().each(u -> {
				u.deathExplosionEffect = Fx.none;
				u.createScorch = false;
				u.createWreck = false;
			});
		}
	}
}
