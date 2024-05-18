package modtools.net.packet;

import arc.util.io.*;
import mindustry.game.Team;
import mindustry.net.*;
import mindustry.type.UnitType;
import modtools.utils.world.WorldUtils;

import static mindustry.Vars.content;

public class UnitSpawnPacket extends Packet {
	private byte[] DATA = NODATA;

	UnitType unit;
	Team     team;
	float    x, y;
	int amount;
	public UnitSpawnPacket(UnitType unit, Team team, float x, float y, int amount) {
		this.unit = unit;
		this.team = team;
		this.x = x;
		this.y = y;
		this.amount = amount;
	}
	public UnitSpawnPacket() {}
	public void read(Reads read, int LENGTH) {
		this.DATA = read.b(LENGTH);
	}
	public void handled(){
    BAIS.setBytes(this.DATA);

		unit = content.unit(READ.i());
		team = Team.get(READ.i());
		x = READ.f();
		y = READ.f();
		amount = READ.i();
	}
	public void handleServer(NetConnection con) {
		if (!HopeCall.checkPrivilege(con)) return;
		WorldUtils.spawnUnit(unit, x, y, amount, team);
	}
	public void write(Writes write) {
		write.i(unit.id);
		write.i(team.id);
		write.f(x);
		write.f(y);
		write.i(amount);
	}
}
