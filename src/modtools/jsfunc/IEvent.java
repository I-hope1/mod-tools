package modtools.jsfunc;

import arc.*;
import arc.files.Fi;
import arc.func.Cons;
import arc.graphics.g2d.TextureAtlas;
import arc.struct.*;
import arc.util.Reflect;
import mindustry.Vars;

import static modtools.Constants.MODS.loadMod;

public interface IEvent {
	class Snapshot {
		/** @see Events#events */
		private static final ObjectMap<Object, Seq<Cons<?>>> events = Reflect.get(Events.class, "events");

		private final ObjectMap<Object, Seq<Cons<?>>> snapshotEvents = new ObjectMap<>();

		private final TextureAtlas snapshotAtlas;
		Snapshot() {
			events.each((k, v) -> {
				snapshotEvents.put(k, v.copy());
			});
			snapshotAtlas = Core.atlas;
		}
		/** 回滚快照 */
		public void rollback() {
			events.clear();
			snapshotEvents.each((k, v) -> {
				events.put(k, v.copy());
			});
			Core.atlas = snapshotAtlas;
		}
	}

	/** 默认实例 */
	Snapshot snapshot = new Snapshot();
	static Snapshot newSnapshot() {
		return new Snapshot();
	}

	static void loadMod(Fi file) throws Exception {
		snapshot.rollback();
		loadMod.invoke(Vars.mods, file, true, true);
	}
}
