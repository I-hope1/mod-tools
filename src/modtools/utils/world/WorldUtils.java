package modtools.utils.world;

import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.graphics.Layer;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.Floor;
import modtools.net.packet.HopeCall;
import modtools.ui.Contents;

import static modtools.ui.Contents.selection;

public interface WorldUtils {
	Seq<Item>     items   = Vars.content.items();
	Seq<Liquid>   liquids = Vars.content.liquids();
	Seq<UnitType> units   = Vars.content.units();
	Seq<Block>    blocks  = Vars.content.blocks();

	WorldDraw uiWD = new WorldDraw(Layer.overlayUI, "ui");

	static void setAir(Tile tile) {
		if (tile.block() != Blocks.air) setBlock(tile, Blocks.air);
	}

	static void setBlock(Tile tile, Block block) {
		if (tile.block() == block) return;
		if (!block.isMultiblock()) {
			setBlock0(tile, block);
			return;
		}
		int offsetx = -(block.size - 1) / 2;
		int offsety = -(block.size - 1) / 2;
		for (int dx = 0; dx < block.size; dx++) {
			for (int dy = 0; dy < block.size; dy++) {
				int  worldx = dx + offsetx + tile.x;
				int  worldy = dy + offsety + tile.y;
				Tile other  = Vars.world.tile(worldx, worldy);

				if (other != null && other.block().isMultiblock() && other.block() == block) {
					return;
				}
				setBlock0(tile, block);
			}
		}
	}
	private static void setBlock0(Tile tile, Block block) {
		HopeCall.setBlock(tile, block, tile.build != null ? tile.team() : Contents.selection.defaultTeam);
	}

	static void spawnUnit(UnitType selectUnit, float x, float y, int amount, Team team) {
		for (int i = 0; i < amount; i++) {
			selectUnit.spawn(team, x, y);
		}
	}
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