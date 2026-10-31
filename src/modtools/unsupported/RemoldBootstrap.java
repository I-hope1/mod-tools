package modtools.unsupported;

import arc.files.Fi;
import arc.util.Log;
import modtools.IntVars;
import modtools.utils.io.FileUtils;
import modtools.utils.reflect.ClassUtils;

import java.io.File;

/**
 * 把 remold agent 提前挂到 system classloader 上。
 *
 * <p>设置类的静态块会链接 {@link HotSwapManager}，而后者直接引用 {@code remold.*}。
 * agent 没挂上时，链接会以 {@code NoClassDefFoundError: remold/HotswapPlugin} 的形式
 * 让整个 mod 加载失败——崩溃点在设置初始化，离真正的原因（agent 还没挂）很远。
 * 因此挂载必须早于任何设置类的初始化。
 *
 * <p>本类刻意不引用任何 {@code remold.*} 类型，才能在挂载之前被安全地加载。
 */
public class RemoldBootstrap {
	private static final String  AGENT_JAR         = "remold-agent.jar";
	private static final String  PLUGIN_JAR        = "remold-plugin-arc.jar";
	private static final String  ASM_JAR           = "remold-asm.jar";
	/** core 的插件加载器读它：逐个 {@code appendToSystemClassLoaderSearch}，再按清单/ServiceLoader 注册 */
	private static final String  PLUGIN_JARS_PROP  = "remold.plugin.jars";
	private static       boolean attempted         = false;
	private static       boolean pluginWarned      = false;

	/** core 是否已经可见，也是挂载是否成功的判据 */
	public static boolean loaded() {
		return ClassUtils.exists("remold.RemoldAgent");
	}

	/**
	 * plugin-arc 是否已经可见。
	 *
	 * <p>plugin-arc 是独立 artifact（{@code remold/arc}、{@code remold/ref}、
	 * {@code remold/profiler}、{@code remold/uihook}），由 core 的插件加载器按
	 * {@code -Dremold.plugin.jars} 追加到 system classloader 后再注册。类可见就说明
	 * 那一轮追加跑过了——注册是同一次遍历里的下一步。第三方用 {@code -javaagent}
	 * 挂自己那份 core 时通常不带它，所以不能拿 {@link #loaded()} 代替这个探测。
	 */
	public static boolean pluginLoaded() {
		return ClassUtils.exists("remold.arc.ArcHotswapPlugin");
	}

	/**
	 * 把 plugin-arc 与它依赖的未重定位 ASM 交给 core 自己的插件加载器。
	 *
	 * <p>属性必须在 agent 第一次 {@code initConfig} 之前写好：{@code agentmain} 一进来就会
	 * 读它，晚了插件那一轮就过去了（后续 {@code RemoldAgent.init} 还会再跑一次 initConfig，
	 * 但第一次挂载后到那之前，插件相关的类都链不上）。
	 *
	 * <p>属性里只能是本地路径：agent 拿它开 {@link java.util.jar.JarFile}，而打包后这些资源
	 * 躺在 mod 的 zip 里。
	 */
	private static void setupPluginJars() {
		StringBuilder paths = new StringBuilder(System.getProperty(PLUGIN_JARS_PROP, "").trim());
		for (String name : new String[]{PLUGIN_JAR, ASM_JAR}) {
			Fi lib = IntVars.libs.child(name);
			if (!lib.exists()) {
				Log.warn("[Remold] @ is missing, plugin-arc cannot be loaded.", lib);
				continue;
			}
			Fi tmp = FileUtils.copyToTmp(lib);
			tmp.file().deleteOnExit();
			if (paths.length() > 0) paths.append(File.pathSeparatorChar);
			paths.append(tmp.path());
		}
		if (paths.length() == 0) return;
		System.setProperty(PLUGIN_JARS_PROP, paths.toString());
	}

	/** 缺插件只提示一次，避免每次热重载都刷屏 */
	public static void warnMissingPluginOnce() {
		if (pluginWarned) return;
		pluginWarned = true;
		Log.warn("[Remold] @ is loaded but plugin-arc is not; "
		         + "arc integration (UI breaker, GL timer, deferred removal) is disabled. "
		         + "Check that libs/remold-plugin-arc.jar and libs/remold-asm.jar exist "
		         + "and that -Dremold.plugin.jars points at them.", "remold.RemoldAgent");
	}

	/** 幂等：重复调用只会在第一次真正尝试挂载 */
	public static void attach() {
		if (attempted) return;
		attempted = true;
		try {
			// 插件必须在 agentmain → initConfig 之前登记好
			setupPluginJars();
			// UtilsAgentManager 的静态块会顺带挂上 utils-agent 与 panama 后端
			UtilsAgentManager.init();
			if (loaded()) {
				// 第三方 -javaagent 可能只带了 core，此时插件层是空的
				if (!pluginLoaded()) warnMissingPluginOnce();
				return;
			}

			Fi lib = IntVars.libs.child(AGENT_JAR);
			if (!lib.exists()) {
				Log.warn("[Remold] @ not found, hotswap disabled.", lib);
				return;
			}
			Fi tmp = FileUtils.copyToTmp(lib);
			tmp.file().deleteOnExit();
			UtilsAgentManager.attachAgent(tmp.path(), true, "");
			if (!loaded()) Log.warn("[Remold] Agent attached but remold.RemoldAgent is still missing.");
		} catch (Throwable e) {
			Log.err("[Remold] Failed to attach agent.", e);
		}
	}
}
