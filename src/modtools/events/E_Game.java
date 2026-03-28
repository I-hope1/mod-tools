package modtools.events;

import arc.Core;
import arc.files.Fi;
import arc.func.Cons;
import arc.scene.ui.layout.Table;
import arc.util.OS;
import mindustry.Vars;
import mindustry.core.Version;
import modtools.annotations.settings.*;

import java.util.Locale;

import static modtools.events.E_Game.DEF.*;

@SettingsInit
public enum E_Game implements ISettings {
	/** @see ISettings#$(float, float, float, float) */
	@FlushField
	renderer_min_zoom(float.class, it -> it.$(Vars.renderer.minZoom, Math.min(0.1f, minZoom), maxZoom, 0.1f)),
	/** @see ISettings#$(float, float, float, float) */
	@FlushField
	renderer_max_zoom(float.class, it -> it.$(Vars.renderer.maxZoom, maxZoom, Math.max(24f, maxZoom), 0.1f)),
	/** @see ISettings#$(int, int, int, int) */
	@FlushField
	max_schematic_size(int.class, it -> it.$(Vars.maxSchematicSize, Vars.maxSchematicSize, 500)) {
		public void build(String prefix, Table table) {
			if (Version.number < 136) return;
			super.build(prefix, table);
		}
	},

	force_enable_gl3 {
		public boolean isSwitchOn() {
			try {
				return OS.isWindows && Core.graphics.getGLVersion().vendorString.toLowerCase(Locale.ROOT).contains("intel");
			} catch (Exception e) {
				return false;
			}
		}
	}
	//
	;

	interface DEF {
		/** @see mindustry.core.Renderer#minZoom */
		float minZoom = 1.5f;
		/** @see mindustry.core.Renderer#maxZoom */
		float maxZoom = 6f;
	}

	static {
		Runnable r = () -> {
			if (!OS.isWindows) return;
			Fi file = new Fi(OS.getAppDataDirectoryString("Mindustry")).child("was_intel_gpu");
			file.writeString(force_enable_gl3.enabled() ? "2" : "1");
		};
		r.run();
		force_enable_gl3.onChange(r);
	}

	E_Game() { }
	E_Game(Class<?> cl, Cons<ISettings> builder) { }
}
