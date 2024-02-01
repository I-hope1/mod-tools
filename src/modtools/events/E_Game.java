package modtools.events;

import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.core.Version;
import modtools.annotations.SettingsInit;

import static modtools.events.E_Game.DEF.*;

@SettingsInit
public enum E_Game implements ISettings {
	renderer_min_zoom(float.class, Math.min(0.1f, minZoom), maxZoom),
	renderer_max_zoom(float.class, maxZoom, Math.max(14f, maxZoom)),
	max_schematic_size(int.class, Vars.maxSchematicSize, 500) {
		public void build(String prefix, Table table) {
			if (Version.number < 136) return;
			super.build(prefix, table);
		}
	};
	interface DEF {
		float minZoom = Vars.renderer.minZoom;
		float maxZoom = Vars.renderer.maxZoom;
	}
	static {
		renderer_min_zoom.def(minZoom);
		renderer_max_zoom.def(maxZoom);
		max_schematic_size.def(Vars.maxSchematicSize);
	}

	E_Game(Class<?> ccl, float... args) {}
	E_Game(Class<?> ccl, int... args) {}
}
