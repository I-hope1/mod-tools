package modtools.events;

import arc.func.Cons;
import arc.struct.*;

/* copy from arc.Events */
public class MyEvents {
	private static final ObjectMap<Object, Seq<Cons<?>>> events = new ObjectMap<>();

	private final ObjectMap<Object, Seq<Cons<?>>> insEvents    = new ObjectMap<>();
	public static Seq<MyEvents>                   allInstances = new Seq<>();
	public MyEvents() {
		allInstances.add(this);
	}
	public void removeIns() {
		insEvents.clear();
		allInstances.remove(this);
	}
	/** Handle an event by class. */
	public <T extends Enum<T>> void onIns(Enum<T> type, Cons<T> listener) {
		insEvents.get(type, () -> new Seq<>(Cons.class)).add(listener);
	}
	/** Fires an enum trigger. */
	public <T extends Enum<T>> void fireIns(Enum<T> type) {
		Seq<Cons<?>> listeners = insEvents.get(type);

		if (listeners != null) {
			int    len   = listeners.size;
			Cons[] items = listeners.items;
			for (int i = 0; i < len; i++) {
				items[i].get(type);
			}
		}
	}

	/* ---------------------------------------- */


	/* private static final ObjectMap<Class<?>, Data>       entries = new ObjectMap<>();
	public static void register(Class<?> cl, Data data) {
		entries.put(cl, data);
	}
	static {
		register(JSFuncDisplay.class, MySettings.D_JSFUNC_DISPLAY);
	} */

	public static MyEvents current = null;
	/** Handle an event by class. */
	public static <T extends Enum<T>> void on(Enum<T> type, Cons<T> listener) {
		(current != null ? current.insEvents : events).get(type, () -> new Seq<>(Cons.class)).add(listener);
	}


	/** Fires an enum trigger. */
	public static <T extends Enum<T>> void fire(Enum<T> type) {
		Seq<Cons<?>> listeners = events.get(type);

		if (listeners != null) {
			int    len   = listeners.size;
			Cons[] items = listeners.items;
			for (int i = 0; i < len; i++) {
				items[i].get(type);
			}
		}
		allInstances.each(e -> e.fireIns(type));
	}
}
