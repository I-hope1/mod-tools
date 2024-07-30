package modtools.events;

import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.core.Version;
import modtools.annotations.settings.*;

import static modtools.events.E_Game.DEF.*;

@SettingsInit
public enum E_Game implements ISettings {
	/** @see ISettings#$(Float) */
	@FlushField
	renderer_min_zoom(float.class, Vars.renderer.minZoom, Math.min(0.1f, minZoom), maxZoom),
	/** @see ISettings#$(Float) */
	@FlushField
	renderer_max_zoom(float.class, Vars.renderer.maxZoom, maxZoom, Math.max(14f, maxZoom)),
	/** @see ISettings#$(Integer) */
	@FlushField
	max_schematic_size(int.class, Vars.maxSchematicSize, Vars.maxSchematicSize, 500) {
		public void build(String prefix, Table table) {
			if (Version.number < 136) return;
			super.build(prefix, table);
		}
	};

	interface DEF {
		float minZoom = Vars.renderer.minZoom;
		float maxZoom = Vars.renderer.maxZoom;
	}

	E_Game(Class<?> cl, float... args) {}
	E_Game(Class<?> cl, int... args) {}
}
