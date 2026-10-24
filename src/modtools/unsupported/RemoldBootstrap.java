package modtools.unsupported;

import arc.files.Fi;
import arc.util.Log;
import modtools.IntVars;
import modtools.utils.io.FileUtils;
import modtools.utils.reflect.ClassUtils;

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
	private static final String  AGENT_JAR    = "remold-agent.jar";
	private static       boolean attempted    = false;
	private static       boolean pluginWarned = false;

	/** core 是否已经可见，也是挂载是否成功的判据 */
	public static boolean loaded() {
		return ClassUtils.exists("remold.RemoldAgent");
	}

	/**
	 * plugin-arc 扩展是否可见。
	 *
	 * <p>{@link #loaded()} 只证明 core 在。plugin-arc 是独立 artifact
	 * （{@code remold/arc}、{@code remold/ref}、{@code remold/profiler}、{@code remold/uihook}），
	 * 第三方用 {@code -javaagent} 挂自己那份 core 时通常不带它。此时 arc 集成相关的类
	 * 全部无法链接，必须单独探测，不能拿 {@link #loaded()} 代替。
	 */
	public static boolean pluginLoaded() {
		return ClassUtils.exists("remold.arc.ArcHotswapPlugin");
	}

	/** 缺插件只提示一次，避免每次热重载都刷屏 */
	public static void warnMissingPluginOnce() {
		if (pluginWarned) return;
		pluginWarned = true;
		Log.warn("[Remold] @ is loaded but plugin-arc is missing; "
		         + "arc integration (UI breaker, GL timer, deferred removal) is disabled. "
		         + "Use a self-contained remold-agent.jar that bundles plugin-arc.", "remold.RemoldAgent");
	}

	/** 幂等：重复调用只会在第一次真正尝试挂载 */
	public static void attach() {
		if (attempted) return;
		attempted = true;
		try {
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
