package modtools.events;

import arc.func.*;
import arc.util.OS;
import arc.util.serialization.Jval;
import modtools.IntVars;
import modtools.annotations.settings.*;
import modtools.utils.reflect.ClassUtils;
import remold.RemoldAgent;

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
	              "kotlin.", "kotlinx.", "arc.", "mindustry.", "rhino.", "mindustryX.",
	              "remold."})),
	@Switch(dependency = "hot_swap")
	retransform_loaded,
	@Switch(dependency = "hot_swap")
	hotswap_plus,
	@Switch(dependency = "hot_swap")
	hotswap_event,
	@Switch(dependency = "hot_swap")
	force_reinit,
	@Switch(dependency = "hot_swap")
	lambda_align,
	@Switch(dependency = "hot_swap")
	ui_hook,
	@Switch(dependency = "hot_swap")
	cell_property_ref,

	_1,
	// ------------
	@Switch(dependency = "hot_swap")
	profile,
	@Switch(dependency = "profile")
	capture_locals,

	_2,

	@Switch(dependency = "hot_swap")
	capture_exceptions,
	@Switch(dependency = "capture_exceptions")
	capture_all_exceptions,

	_3,

	/* dynamic_jdwp {
		public boolean isSwitchOn() {
			return IntVars.isDesktop();
		}
	},
	@Switch(dependency = "dynamic_jdwp")
	dynamic_jdwp_port(int.class, i -> i.intField(5005, 1, 65535)), */
	android_input_fix {
		public boolean isSwitchOn() {
			return OS.isAndroid;
		}
	};

	E_Hook(Class<?> type, Cons<ISettings> builder) { }
	E_Hook() { }

	/**
	 * 启动阶段批量写属性，期间不调 {@code initConfig()}。
	 * 下面 9 个设置各自 {@code runAndOnChange} 会立刻执行一次，若每次都 init，
	 * 整套配置（每次 11 行）会被原样打印 9 遍；真正的 init 由
	 * {@code HotSwapManager.start()} 统一做一次。
	 */
	private static boolean batchApplying = true;

	static {
		init();
	}

	static void init() {
		lambda_align.defTrue();
		Func<ISettings, String> func = x -> x.getString().trim();

		hotswapOnChange(redefine_mode, func);
		hotswapOnChange(hotswap_blacklist, x -> String.join(",", x.getArray().map(Jval::asString)));
		hotswapOnChange(hotswap_plus, func);
		hotswapOnChange(hotswap_event, func);
		hotswapOnChange(force_reinit, func);
		hotswapOnChange(lambda_align, func);
		hotswapOnChange(ui_hook, func);
		hotswapOnChange(cell_property_ref, func);

		setProperty(retransform_loaded);
		setProperty(capture_locals);
		setProperty(capture_exceptions);
		hotswapOnChange(capture_all_exceptions, func);

		batchApplying = false;
	}
	private static void setProperty(E_Hook _enum) {
		System.setProperty("remold.agent." + _enum.name(), _enum.getString());
	}

	static void hotswapOnChange(ISettings setting, Func<ISettings, String> func) {
		setting.runAndOnChange(() -> {
			System.setProperty("remold.agent." + setting.name(), func.get(setting));
			if (batchApplying) return;
			try {
				RemoldAgent.initConfig();
			} catch (NoClassDefFoundError _) { }
		});
	}
	public enum RedefineMode {
		inject,
		lazy_load,
	}
}
