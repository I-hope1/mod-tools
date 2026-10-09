package nipx.annotation;

import java.lang.annotation.*;

/**
 * 热重载生命周期回调注解。
 *
 * <p>标记在方法上，指示在类被热替换（Redefine / Retransform）且补丁（如 {@code InitFix}）应用完成后，
 * 由热更管线自动触发该方法以执行状态刷新或资源重建逻辑。</p>
 *
 * <h2>方法规则</h2>
 * <ul>
 *   <li><b>静态方法</b>（{@code static}）：直接无参调用一次（例如重置单例缓存、重新绑定全局监听器等）。</li>
 *   <li><b>实例方法</b>（非 static）：通过堆遍历（优先使用 {@code LibTool.getInstances}，降级走
 *       {@code InstanceTracker.getInstances}）检索当前 JVM 中目标类的所有存活实例，
 *       并逐一无参调用该方法。</li>
 *   <li>方法应为无参方法；若执行抛出异常，热更管线会捕获并记录错误日志，不会导致整个热更流程崩溃。</li>
 * </ul>
 *
 * <h2>执行线程与上下文</h2>
 * <p>本回调在<b>热更线程</b>上同步执行。若回调涉及 UI 线程（如 Arc/Mindustry 主循环线程）的渲染组件重建，
 * 建议在回调内部显式派发至主线程（例如使用 {@code Core.app.post(...)}）。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OnReload {
}
