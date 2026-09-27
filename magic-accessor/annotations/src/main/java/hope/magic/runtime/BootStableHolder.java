package hope.magic.runtime;

import jdk.internal.vm.annotation.Stable;

/**
 * 生产运行时受信任的 @Stable 静态字段持有者。
 * <p>由 BootstrapClassLoader 加载（{@code loader == null}），满足 HotSpot C2
 * {@code ciField::is_stable()} / {@code is_trusted_loader()} 受信信任边界要求，
 * 专为 JavaScript 引擎对象内存布局与 Shape 寻址提供零跳转常量折叠支持。</p>
 */
public final class BootStableHolder {

	private BootStableHolder() {
	}

	@Stable
	public static long[] JS_PRIM_OFFSETS = new long[8];

	@Stable
	public static long[] JS_OBJ_OFFSETS = new long[8];

	@Stable
	public static long[] SHAPE_KEY_OFFSETS = new long[4];

	@Stable
	public static long[] SHAPE_TYPE_OFFSETS = new long[4];
}
