package modtools.events;

import arc.func.Cons;
import arc.util.*;
import arc.util.serialization.Jval;
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
	hotswap_blacklist(String[].class, i -> i.array(
	 new String[]{"java.", "javax.", "jdk.", "sun.",
  "kotlin.", "kotlinx.", "arc.", "mindustry.",
  "nipx."})),

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
		System.setProperty("nipx.agent.redefine_mode", redefine_mode.getString().trim());
		System.setProperty("nipx.agent.hotswap_blacklist", String.join(",", hotswap_blacklist.getArray().map(Jval::asString)));
	}
	public enum RedefineMode {
		inject,
		lazy_load,
	}
}
