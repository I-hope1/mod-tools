package nipx;

import java.lang.instrument.ClassDefinition;
import java.util.List;

/**
 * 热重载批次生命周期监听器。
 *
 * <p><b>语义规范：</b></p>
 * <ul>
 *   <li><b>内部管线批次（{@link #afterInternalBatch}）</b>：
 *       经由 Agent 自身文件监控或主动重载管线触发。
 *       此时所有类的字节码均已经过桥接转换、拓扑对齐、安全门（LayoutGate）检测，
 *       并已由 JVM 批量重定义成功且执行了 InitFix 存量初始化。
 *       UI 重建、容器刷新等重量级操作应当且仅当在此方法中进行<b>单批统一处理</b>，
 *       坚决避免在单类回调中多次刷新导致界面闪烁与卡顿。</li>
 *   <li><b>外部检测重定义（{@link #onExternalClassRedefined}）</b>：
 *       由外部调试器（如 IntelliJ IDEA JDWP）或第三方 Agent 直接重定义触发。
 *       由于 JVM 原生 JDWP 协议不提供完成回调，此方法是在底层 {@code transform} 拦截织入后，
 *       由异步验证调度器确认目标类在 JVM 堆中已实际就绪后延迟触发。</li>
 * </ul>
 */
public interface HotswapBatchListener {

	/**
	 * 内部主动批次（modtools 文件变更 / 手动重载）重定义全部生效后的回调。
	 *
	 * @param redefinedClasses 本批成功重定义的类定义列表
	 */
	default void afterInternalBatch(List<ClassDefinition> redefinedClasses) {}

	/**
	 * 外部（IDE JDWP / 第三方 Agent）重定义生效检测回调。
	 * 由于 JVM JDWP 不提供完成通知，本回调由异步验证调度器在复核类已生效后触发。
	 *
	 * @param clazz      生效的类
	 * @param wovenBytes 实际在 JVM 运行的字节码
	 */
	default void onExternalClassRedefined(Class<?> clazz, byte[] wovenBytes) {}
}
