package modtools.utils.world;

import mindustry.entities.units.UnitController;
import mindustry.gen.*;
import modtools.utils.reflect.FieldUtils;

public interface UnitUtils {
	static boolean forceRemove(Unit u) {
		u.remove();
		if (!Groups.unit.contains(unit -> unit == u)) return true;
		Groups.all.remove(u);
		Groups.unit.remove(u);
		Groups.sync.remove(u);
		Groups.draw.remove(u);
		u.team.data().updateCount(u.type, -1);

		boolean        ok         = FieldUtils.setBoolean(u, FieldUtils.getFieldAccessAll(u.getClass(), "added"), false);
		UnitController controller = u.controller();
		if (controller == null) return false;
		controller.removed(u);
		return ok;
	}
	static boolean kill(Unit u) {
		Call.unitDeath(u.id);
		return u.isAdded();
	}
}
