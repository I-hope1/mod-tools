package modtools.net.packet;

import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.*;
import mindustry.type.UnitType;
import mindustry.world.*;
import modtools.ui.content.world.UnitSpawn;
import modtools.utils.world.WorldUtils;

import static mindustry.Vars.net;

public class HopeCall {
	public static void init() {
		Net.registerPacket(UnitSpawnPacket::new);
		Net.registerPacket(SetBlockPacket::new);
	}

	/** @return {@code true} if the player has a privilege.  */
	static boolean checkPrivilege(NetConnection con) {
		return con.player.admin || con.player.isLocal();
	}

	public static void setBlock(Block block, Tile tile, Team team) {
		if (net.server() || !net.active()) {
			WorldUtils.setBlock(tile, block, team);
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
}
