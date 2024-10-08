package modtools.events;

import arc.struct.Seq;
import modtools.utils.SR;

import java.util.HashMap;

/* copy from arc.Events */
@SuppressWarnings("unchecked")
public class MyEvents {
	private static final HashMap<Object, Seq> events = new HashMap<>();

	private final HashMap<Object, Seq> insEvents    = new HashMap<>();
	public static Seq<MyEvents>        allInstances = new Seq<>();

	public MyEvents() {
		allInstances.add(this);
	}
	public static void dispose() {
		allInstances.clear();
	}
	public void removeIns() {
		insEvents.forEach((_, seq) -> seq.clear());
		insEvents.clear();
		allInstances.remove(this);
	}
	/** Handle an event by class. */
	public <T extends Enum<T>> void onIns(Enum<T> type, Runnable listener) {
		insEvents.computeIfAbsent(type, k -> new Seq<>(Runnable.class))
		 .add(listener);
	}
	/** Fires an enum trigger. */
	public <T extends Enum<T>> void fireIns(Enum<T> type) {
		Seq<Runnable> listeners = insEvents.get(type);

		if (listeners != null) {
			int    len   = listeners.size;
			Runnable[] items = listeners.items;
			for (int i = 0; i < len; i++) {
				items[i].run();
			}
		}
	}

	/** Handle an event by class. */
	public void onIns(Object type, Runnable listener) {
		insEvents.computeIfAbsent(type, k -> new Seq<>(Runnable.class))
		 .add(listener);
	}
	/** Fires an enum trigger. */
	public void fireIns(Object type) {
		Seq<Runnable> listeners = insEvents.get(type);

		if (listeners != null) {
			int        len   = listeners.size;
			Runnable[] items = listeners.items;
			for (int i = 0; i < len; i++) {
				items[i].run();
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
	/* ------------- for enum ---------- */
	/** Handle an event by class. */
	public static <T extends Enum<T>> void on(Enum<T> type, Runnable listener) {
		(current != null ? current.insEvents : events)
		 .computeIfAbsent(type, k -> new Seq<>(Runnable.class))
		 .add(listener);
	}

	/** Fires an enum trigger. */
	public static <T extends Enum<T>> void fire(Enum<T> type) {
		Seq<Runnable> listeners = events.get(type);

		if (listeners != null) {
			listeners.each(Runnable::run);
		}
		allInstances.each(e -> e.fireIns(type));
	}

	/* ------------- for object ---------- */
	public static void on(Object type, Runnable listener) {
		(current != null ? current.insEvents : events)
		 .computeIfAbsent(type, _ -> new Seq<>(Runnable.class))
		 .add(listener);
	}
	public static void fire(Object type) {
		SR.of((Seq<Runnable>) events.get(type))
		 .ifPresent(l -> l.each(Runnable::run));
	}
}
