package modtools.utils.jsfunc;

import arc.struct.Seq;
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
}
