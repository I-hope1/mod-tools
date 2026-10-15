package nipx;

import java.lang.instrument.Instrumentation;

/**
 * 热重载插件契约。
 * 允许独立扩展模块（如 Arc / Mindustry 插件）接入 Agent 生命周期。
 */
public interface HotswapPlugin {

	/** 插件唯一标识名 */
	String getName();

	/**
	 * Agent 核心就绪回调。
	 *
	 * @param inst JVM Instrumentation 实例
	 */
	default void onAgentInit(Instrumentation inst) {}

	/** 配置重载回调 */
	default void onConfigReload() {}
}
