package modtools.events;

import arc.func.*;
import arc.util.OS;
import arc.util.serialization.Jval;
import modtools.IntVars;
import modtools.annotations.settings.*;
import modtools.utils.reflect.ClassUtils;
import nipx.HotSwapAgent;

@SettingsInit
public enum E_Hook implements ISettings {
	hot_swap {
		public boolean isSwitchOn() {
			return IntVars.isDesktop() && ClassUtils.exists("sun.tools.attach.HotSpotVirtualMachine");
		}
	},
	@Switch(dependency = "hot_swap")
	hot_swap_watch_paths(String[].class, i -> i.array(null)),
	@Switch(dependency = "hot_swap")
	// 重定义模式
	redefine_mode(RedefineMode.class, i -> i.buildEnum(RedefineMode.lazy_load, RedefineMode.class)),
	@Switch(dependency = "hot_swap")
	hotswap_blacklist(String[].class, i -> i.array(
	 new String[]{"java.", "javax.", "jdk.", "sun.",
	              "kotlin.", "kotlinx.", "arc.", "mindustry.", "rhino.", "mindustryX",
	              "nipx."})),
	@Switch(dependency = "hot_swap")
	retransform_loaded,
	@Switch(dependency = "hot_swap")
	hotswap_event,
	@Switch(dependency = "hot_swap")
	lambda_align,


	// ------------

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
	E_Hook() { }

	static {
		init();
	}

	static void init() {
		hotswapOnChange(redefine_mode, () -> redefine_mode.getString().trim());
		hotswapOnChange(hotswap_blacklist, () -> String.join(",", hotswap_blacklist.getArray().map(Jval::asString)));
		hotswapOnChange(hotswap_event, () -> hotswap_event.getString().trim());
		hotswapOnChange(lambda_align, () -> lambda_align.getString().trim());
		retransform_loaded.defTrue();
		System.setProperty("nipx.agent.retransform_loaded", retransform_loaded.getString());
	}
	static void hotswapOnChange(ISettings setting, Prov<String> prov) {
		setting.runAndOnChange(() -> {
			System.setProperty("nipx.agent." + setting.name(), prov.get());
			try {
				HotSwapAgent.initConfig();
			} catch (NoClassDefFoundError _) { }
		});
	}
	public enum RedefineMode {
		inject,
		lazy_load,
	}
}
