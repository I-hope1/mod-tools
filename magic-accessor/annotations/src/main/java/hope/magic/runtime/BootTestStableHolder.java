package hope.magic.runtime;

import jdk.internal.vm.annotation.Stable;
import java.lang.invoke.MethodHandle;

/**
 * JMH 基准测试与微架构验证专用的 @Stable 字段持有者。
 * <p>由 BootstrapClassLoader 加载，用于隔离 Benchmark 测试字段与核心生产运行时字段。</p>
 */
public class BootTestStableHolder {

	@Stable
	public static MethodHandle STATIC_STABLE_MH;

	@Stable
	public static MethodHandle[] STATIC_STABLE_TABLE = new MethodHandle[8];

	@Stable
	public static MagicInvoker STATIC_STABLE_INVOKER;

	@Stable
	public static MagicInvoker[] STATIC_STABLE_INVOKER_TABLE = new MagicInvoker[8];

	@Stable
	public MethodHandle instanceStableMh;

	@Stable
	public MethodHandle[] instanceStableTable = new MethodHandle[8];

	@Stable
	public MagicInvoker instanceStableInvoker;

	@Stable
	public MagicInvoker[] instanceStableInvokerTable = new MagicInvoker[8];

	@Stable
	public static long[][] TABLE_PROP_SHAPE;

	@Stable
	public static long[][] TABLE_SHAPE_PROP;
}
