package modtools.net.packet;

import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.net.*;
import mindustry.type.UnitType;
import mindustry.world.*;
import modtools.utils.world.WorldUtils;

import static mindustry.Vars.net;

public class HopeCall {
	public static void registerPacket() {
		Net.registerPacket(UnitSpawnPacket::new);
		Net.registerPacket(SetBlockPacket::new);
	}

	/** @return {@code true} if the player has a privilege.  */
	static boolean checkPrivilege(NetConnection con) {
		return con.player.admin || con.player.isLocal();
	}

	public static void setBlock(Tile tile, Block block, Team team) {
		if (!net.active() || net.server()) {
			tile.setBlock(block, team);
		}
		if (net.server() || net.client()) {
			net.send(new SetBlockPacket(block, tile, team), true);
		}
	}
	public static void spawnUnit(UnitType selectUnit, float x, float y, int amount, Team team) {
		if (net.server() || !net.active()) {
			WorldUtils.spawnUnit(selectUnit, x, y, amount, team);
		}
		if (net.client() || net.server()) {
			UnitSpawnPacket packet = new UnitSpawnPacket(selectUnit, team, x, y, amount);
			net.send(packet, true);
		}
	}
	public static void setFloor(Tile tile, Block floor) {
		tile.setFloorNet(floor);
	}
	public static void setOverlay(Tile tile, Block overlay) {
		Call.setOverlay(tile, overlay);
	}
	public static void setFloorUnder(Tile tile, Block floor) {
		tile.setFloorNet(floor, tile.overlay());
	}
}
