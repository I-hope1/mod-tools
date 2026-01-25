package modtools.events;

import arc.func.Cons;
import arc.util.OS;
import modtools.IntVars;
import modtools.annotations.settings.*;
import modtools.utils.reflect.ClassUtils;

@SettingsInit
public enum E_Hook implements ISettings {
	hot_swap {
		public boolean isSwitchOn() {
			return IntVars.isDesktop() && ClassUtils.exists("sun.tools.attach.HotSpotVirtualMachine");
		}
	},
	@Switch(dependency = "hot_swap")
	hot_swap_watch_paths(String[].class, i -> i.array(null)),
	// 重定义模式
	redefine_mode(RedefineMode.class, i -> i.buildEnum(RedefineMode.lazy_load, RedefineMode.class)),

	dynamic_jdwp {
		public boolean isSwitchOn() {
			return IntVars.isDesktop();
		}
	},
	@Switch(dependency = "dynamic_jdwp")
	dynamic_jdwp_port(int.class, i -> i.intField(5005, 1, 65535)),
	android_input_fix {
		public boolean isSwitchOn() {
			return OS.isAndroid;
		}
	};
	E_Hook(Class<?> type, Cons<ISettings> builder) { }
	E_Hook(){}

	static {
		System.setProperty("nipx.agent.redefine_mode", redefine_mode.getString());
	}
	public enum RedefineMode {
		inject,
		lazy_load,
	}
}
