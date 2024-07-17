package modtools.jsfunc;

import arc.Events;
import arc.func.Cons;
import arc.struct.*;
import arc.util.Reflect;

import static modtools.jsfunc.IEvent.$.*;

public interface IEvent {
	interface $ {
		/** @see Events#events */
		ObjectMap<Object, Seq<Cons<?>>> events    = Reflect.get(Events.class, "events");
		ObjectMap<Object, Seq<Cons<?>>> snapshots = new ObjectMap<>();
	}
	static void snapshotEvents() {
		snapshots.clear();
		events.each((k, v) -> {
			snapshots.put(k, v.copy());
		});
	}
	static void rollbackEvents() {
		events.clear();
		snapshots.each((k, v) -> {
			events.put(k, v.copy());
		});
	}
}
