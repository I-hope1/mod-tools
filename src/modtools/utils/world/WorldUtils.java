package modtools.utils.world;

import arc.util.io.*;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.graphics.Layer;
import mindustry.net.*;
import mindustry.type.UnitType;
import mindustry.world.*;
import modtools.net.packet.*;
import modtools.ui.Contents;

import static mindustry.Vars.*;

public class WorldUtils {
	public static WorldDraw uiWD = new WorldDraw(Layer.overlayUI, "ui");

	public static void setBlock(Tile tile, Block block) {
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
		HopeCall.setBlock(block, tile, tile.build != null ? tile.team() : Contents.selection.defaultTeam);
	}
	public static void setBlock(Tile tile, Block block, Team team) {
		tile.setBlock(block, team);
	}
	public static void spawnUnit(UnitType selectUnit, float x, float y, int amount, Team team) {
		for (int i = 0; i < amount; i++) {
			selectUnit.spawn(team, x, y);
		}
	}


}