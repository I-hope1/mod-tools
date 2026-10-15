package nipx;

/**
 * 栈遍历透明帧判定 SPI。
 *
 * <p>在定位孤儿 Lambda 的真正调用者时，用于识别并穿透中间的动态代理、包装器或辅助调度帧
 * （例如 {@code CellPropertyRef} 代理、JDK 动态代理帧等）。</p>
 */
@FunctionalInterface
public interface TransparentFramePredicate {

	/**
	 * 判断当前栈帧是否为应穿透的透明帧。
	 *
	 * @param className 栈帧的全限定类名（点分隔）
	 * @return true 表示该帧为透明帧，应继续向下查找真正的调用者
	 */
	boolean isTransparent(String className);
}
