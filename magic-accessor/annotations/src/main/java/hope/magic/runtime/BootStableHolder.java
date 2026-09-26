package hope.magic.runtime;

import jdk.internal.vm.annotation.Stable;
import java.lang.invoke.MethodHandle;

public class BootStableHolder {

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
	public static long[] JS_PRIM_OFFSETS = new long[8];

	@Stable
	public static long[] JS_OBJ_OFFSETS = new long[8];

	@Stable
	public static long[] SHAPE_KEY_OFFSETS = new long[4];

	@Stable
	public static long[] SHAPE_TYPE_OFFSETS = new long[4];
}
