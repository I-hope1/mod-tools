package modtools.ui.packet;

import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.net.Net;
import mindustry.net.Packet;

public class MyPacket extends Packet {
	private byte[] DATA;
	public boolean aBoolean = true;

	public MyPacket() {
		this.DATA = NODATA;
	}

	public void write(Writes WRITE) {
		WRITE.bool(aBoolean);
	}

	public void read(Reads READ, int LENGTH) {
		this.DATA = READ.b(LENGTH);
	}

	public void handled() {
		BAIS.setBytes(this.DATA);
		aBoolean = READ.bool();
//		Log.info(aBoolean);
	}

	public void handleClient() {
//        Vars.ui.showInfoToast("ok:" + aBoolean, 0.1f);
//        Log.info("ok:" + aBoolean);
	}

	public static void register() {
		Net.registerPacket(MyPacket::new);
	}
}