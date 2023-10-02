package modtools.utils.world;

import arc.util.Reflect;
import mindustry.entities.units.UnitController;
import mindustry.gen.*;
import modtools.annotations.OptimizeReflect;
import modtools.utils.reflect.FieldUtils;

@OptimizeReflect
public class UnitUtils {
	public static boolean forceRemove(Unit u) {
		@OptimizeReflect(isSetter = true)
		boolean ok = FieldUtils.set$$(UnitEntity.class, u, "added", false);
		@OptimizeReflect
		UnitController controller = Reflect.get(UnitEntity.class, u, "controller");
		if (controller == null) return false;
		controller.removed(u);
		return ok;
	}
	public static boolean kill(Unit u) {
		@OptimizeReflect
		boolean b = Reflect.get(UnitEntity.class, u, "added");
		return b;
	}
}
