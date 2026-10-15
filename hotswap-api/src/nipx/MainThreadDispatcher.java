package nipx;

/**
 * 主线程调度器契约。
 *
 * <p>默认实现为 {@code Runnable::run}（在当前线程就地执行），
 * 上层 GUI 宿主（如 Mindustry / Arc）可在启动时注册其事件循环派发器（如 {@code Core.app::post}）。</p>
 */
@FunctionalInterface
public interface MainThreadDispatcher {

	/**
	 * 将任务调度到主线程执行。
	 *
	 * @param task 待执行任务
	 */
	void dispatch(Runnable task);
}
