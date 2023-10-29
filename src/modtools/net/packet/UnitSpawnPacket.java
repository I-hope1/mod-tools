package modtools.net.packet;

import arc.util.io.*;
import mindustry.game.Team;
import mindustry.net.*;
import mindustry.type.UnitType;
import modtools.utils.world.WorldUtils;

import static mindustry.Vars.content;

public class UnitSpawnPacket extends Packet {
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
	public void read(Reads read) {
		unit = content.unit(read.i());
		team = Team.get(read.i());
		x = read.f();
		y = read.f();
		amount = read.i();
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
