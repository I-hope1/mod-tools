package modtools.net.packet;

import arc.util.io.*;
import mindustry.game.Team;
import mindustry.net.*;
import mindustry.world.*;
import modtools.utils.world.WorldUtils;

import static mindustry.Vars.*;

public class SetBlockPacket extends Packet {
	Block block;
	Tile  tile;
	Team  team;
	public SetBlockPacket() {}
	public SetBlockPacket(Block block, Tile tile, Team team) {
		this.block = block;
		this.tile = tile;
		this.team = team;
	}
	public void read(Reads read) {
		block = content.block(read.i());
		tile = world.tile(read.i());
		team = Team.get(read.i());
	}
	public void write(Writes write) {
		write.i(block.id);
		write.i(tile.pos());
		write.i(team.id);
	}
	public void handleClient() {
		WorldUtils.setBlock(tile, block, team);
	}
	public void handleServer(NetConnection con) {
		if (!HopeCall.checkPrivilege(con)) return;
		WorldUtils.setBlock(tile, block, team);
		net.send(this, true);
	}
}
