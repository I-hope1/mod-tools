package modtools;

import arc.struct.Seq;
import arc.util.Log;
import arc.util.io.*;
import mindustry.Vars;
import mindustry.io.SaveFileReader.CustomChunk;
import mindustry.io.SaveVersion;
import modtools.content.debug.Tester;
import modtools.jsfunc.IScript;
import modtools.struct.Pair;
import modtools.utils.Tools;

import java.io.*;

public class WorldSaver {
	public WorldSaver() {
		throw new RuntimeException("This class should not be instantiated");
	}
	public enum DataType {
		code,
	}
	public static void load() {
		SaveVersion.addCustomChunk(IntVars.modName, MyCustomChunk.instance);
		Tools.TASKS.add(() -> {
			if (!Vars.state.isGame()) MyCustomChunk.instance.data.clear();
		});
	}
	public static class MyCustomChunk implements CustomChunk {
		public static MyCustomChunk               instance = new MyCustomChunk();
		public final  Seq<Pair<DataType, Object>> data     = new Seq<>();

		public void write(DataOutput stream) throws IOException {
			// Log.info("Writing" + data);

			Writes writes = Writes.get(stream);
			writes.b(data.size);
			data.each(pair -> {
				writes.b(pair.getFirst().ordinal());
				switch (pair.getFirst()) {
					case code -> writes.str(pair.getSecond().toString());
					default -> throw new RuntimeException("Unknown data type: " + pair.getFirst());
				}
			});
		}
		public void read(DataInput stream) throws IOException {
			data.clear();

			Reads reads = Reads.get(stream);
			int   size  = reads.b();
			// Log.info("Reading: " + size);

			for (int i = 0; i < size; i++) {
				DataType value = DataType.values()[reads.b()];
				switch (value) {
					case code -> data.add(new Pair<>(value, reads.str()));
					default -> throw new RuntimeException("Unknown data type: " + value);
				}
			}
			data.each(pair -> {
				DataType value = pair.getFirst();
				switch (value) {
					case code -> {
						try {
							IScript.cx.evaluateString(Tester.customScope, (String) pair.getSecond(), "<WORLD>", 1);
						} catch (Throwable e) { Log.err("Failed to exec code", e); }
					}
					default -> throw new RuntimeException("Unknown data type: " + value);
				}
			});
		}
		public boolean shouldWrite() {
			return true;
		}
		public boolean writeNet() {
			return false;
		}
	}
}
