package modtools.net.packet;

import arc.util.io.*;
import mindustry.game.Team;
import mindustry.net.*;
import mindustry.world.*;

import static mindustry.Vars.*;

public class SetBlockPacket extends Packet {
	private byte[] DATA = NODATA;

	Block block;
	Tile  tile;
	Team  team;
	public SetBlockPacket() {}
	public SetBlockPacket(Block block, Tile tile, Team team) {
		this.block = block;
		this.tile = tile;
		this.team = team;
	}
	public void read(Reads read, int LENGTH) {
		DATA = read.b(LENGTH);
	}
	public void handled(){
    BAIS.setBytes(this.DATA);
		block = content.block(READ.i());
		tile = world.tile(READ.i());
		team = Team.get(READ.i());
	}
	public void write(Writes write) {
		write.i(block.id);
		write.i(tile.pos());
		write.i(team.id);
	}
	public void handleClient() {
		tile.setBlock(block, team);
	}
	public void handleServer(NetConnection con) {
		if (!HopeCall.checkPrivilege(con)) return;
		tile.setBlock(block, team);
		net.send(this, true);
	}
}
