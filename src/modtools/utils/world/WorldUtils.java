package modtools.utils.world;

import mindustry.Vars;
import mindustry.world.*;
import modtools.ui.Contents;

public class WorldUtils {
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
		tile.setBlock(block, tile.build != null ? tile.team() : Contents.selection.defaultTeam);
	}
}