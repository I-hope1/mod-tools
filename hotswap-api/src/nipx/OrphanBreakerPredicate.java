package nipx;

/**
 * 孤儿 Lambda 熔断判定 SPI。
 *
 * <p>当热重载导致部分 Lambda 变为“孤儿桩”（已被删除或由新版本替换）时，
 * 若直接调用者匹配此谓词，则空壳桩将主动抛出 {@link NoSuchMethodError}，
 * 触发调用方插件（如 {@code UpdateRef}）的局部熔断与清理逻辑。</p>
 */
@FunctionalInterface
public interface OrphanBreakerPredicate {

	/**
	 * 判断当前栈帧中的直接调用者是否应当触发孤儿 lambda 熔断。
	 *
	 * @param callerClassName  直接调用者的全限定类名（点分隔，例如 "nipx.ref.UpdateRef"）
	 * @param callerMethodName 直接调用者的方法名（例如 "run"）
	 * @return true 表示应向该调用者抛出 NoSuchMethodError 进行熔断
	 */
	boolean shouldBreak(String callerClassName, String callerMethodName);
}
