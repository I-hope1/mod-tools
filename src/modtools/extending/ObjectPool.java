package modtools.extending;

import arc.Events;
import arc.func.Prov;
import arc.struct.ObjectMap;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.gen.Entityc;
import modtools.annotations.asm.GenPool;
import modtools.utils.Tools;

@SuppressWarnings("unchecked")
@GenPool
public class ObjectPool {
	static final ObjectMap<String, Prov> map = new ObjectMap<>();
	public static void install() {
	}
	public static void reset(Entityc entity) {
	}
	static {
		Events.on(ClientLoadEvent.class, _ -> addMonitor());
	}

	static void addMonitor() {
		// Log.info("Adding monitor!!");
		Vars.content.units().each(unit -> {
			if (map.containsKey(unit.name)) unit.constructor = map.get(unit.name);
		});
		// Vars.content.blocks()
	}
	private static <T> T changeClass(Prov<T> prov) {
		T t = prov.get();
		Class<?> clazz = EntitySampleInterface.visit(t.getClass());
		if (clazz == t.getClass()) {
			return t;
		}
		return Tools.newInstance(t, clazz);
	}
}
