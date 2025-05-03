package modtools.extending;

import arc.Events;
import arc.func.Prov;
import arc.struct.ObjectMap;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.gen.*;
import mindustry.world.Block;
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
	public static void load() {
		Events.on(ClientLoadEvent.class, _ -> {
			Vars.content.blocks().each(block -> {
				if (block.minfo.mod != null) return;
				if (!((block.update || block.destructible) && block.buildType != null)) return;
				if (block.getClass().getClassLoader() != Block.class.getClassLoader()) return;

				var last = block.buildType;
				block.buildType = () -> EntitySampleInterface.changeClass(last.get());
			});
		});
		Events.on(ClientLoadEvent.class, _ -> {
			install();
			flush();
			// Log.info("ok");
		});
	}

	static void flush() {
		// Log.info("Adding monitor!!");
		Vars.content.units().each(unit -> {
			if (unit.constructor.getClass().getNestHost() == EntityMapping.class) {
				unit.constructor = EntityMapping.map(unit.name);
			}
		});
		// Vars.content.blocks()
	}
	private static <T> T changeClass(Prov<T> prov) {
		T        t     = prov.get();
		Class<?> clazz = EntitySampleInterface.visit(t.getClass());
		if (clazz == t.getClass()) {
			return t;
		}
		return Tools.newInstance(t, clazz);
	}
}
