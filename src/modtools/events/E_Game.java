package modtools.events;

import arc.Core;
import arc.files.Fi;
import arc.func.Cons;
import arc.scene.ui.layout.Table;
import arc.util.*;
import mindustry.Vars;
import mindustry.core.*;
import mindustry.graphics.IntelGpuCheck;
import modtools.Constants.IntelCheck;
import modtools.annotations.settings.*;
import modtools.utils.Tools;

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
		/** @see Renderer#minZoom */
		float minZoom = 1.5f;
		/** @see Renderer#maxZoom */
		float maxZoom = 6f;
	}

	static {
		Fi file = new Fi(OS.getAppDataDirectoryString("Mindustry")).child("was_intel_gpu");
		Runnable r = () -> {
			if (!OS.isWindows && !IntelGpuCheck.wasIntel()) return;
			file.writeString(force_enable_gl3.enabled() ? "" : "1");
			try {
				IntelCheck.wasIntel.setBoolean(null, !force_enable_gl3.enabled());
				IntelCheck.checkedLastLaunch.setBoolean(null, true);
			} catch (Exception e) {
				Log.err(e);
			}
		};
		r.run();
		int[] arr = new int[]{1};
		Tools.TASKS.add(() -> {
			if (force_enable_gl3.enabled() && (arr[0]++ % 30 == 0)) {
				r.run();
			}
		});
		force_enable_gl3.onChange(r);
	}

	E_Game() { }
	E_Game(Class<?> cl, Cons<ISettings> builder) { }
}
