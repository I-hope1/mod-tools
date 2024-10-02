package modtools.utils.world;

import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.entities.bullet.BulletType;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.graphics.Layer;
import mindustry.type.*;
import mindustry.world.*;
import modtools.net.packet.HopeCall;
import modtools.ui.Contents;

import static mindustry.Vars.content;
import static modtools.ui.Contents.selection;

public interface WorldUtils {
	Seq<Item>         items   = content.items();
	Seq<Liquid>       liquids = content.liquids();
	Seq<UnitType>     units   = content.units();
	Seq<Block>        blocks  = content.blocks();
	Seq<BulletType>   bullets = content.bullets();
	Seq<SectorPreset> sectors = content.sectors();
	Seq<StatusEffect> status  = content.statusEffects();
	Seq<Planet>       planets = content.planets();

	WorldDraw uiWD = new WorldDraw(Layer.overlayUI, "ui");

	static UnitType unit(int id) {
		return content.unit(id);
	}
	static UnitType unit(String name) {
		return content.unit(name);
	}
	static Block block(int id) {
		return content.block(id);
	}
	static Block block(String name) {
		return content.block(name);
	}
	static Liquid liquid(int id) {
		return content.liquid(id);
	}
	static Liquid liquid(String name) {
		return content.liquid(name);
	}
	static Item item(int id) {
		return content.item(id);
	}
	static Item item(String name) {
		return content.item(name);
	}

	static void setAir(Tile tile) {
		if (tile.build != null) tile.build.remove();
		if (tile.block() != Blocks.air) setBlock(tile, Blocks.air);
	}

	static void setBlock(Tile tile, Block block) {
		if (tile.block() == block) return;
		if (tile.build != null) tile.build.remove();
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
		if (selectUnit == null) return;
		for (int i = 0; i < amount; i++) {
			selectUnit.spawn(team, x, y);
		}
	}
	static void focusWorld(Tile obj) { selection.focusInternal.add(obj); }
	static void focusWorld(Building obj) { selection.focusInternal.add(obj); }
	static void focusWorld(Unit obj) { selection.focusInternal.add(obj); }
	static void focusWorld(Bullet obj) { selection.focusInternal.add(obj); }
	static void focusWorld(Seq<?> obj) { selection.focusInternal.add(obj); }
	static void removeFocusAll() { selection.focusInternal.clear(); }

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
			content.units().each(u -> {
				u.deathExplosionEffect = Fx.none;
				u.createScorch = false;
				u.createWreck = false;
			});
		}
	}
}