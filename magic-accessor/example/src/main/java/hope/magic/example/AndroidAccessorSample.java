package hope.magic.example;

import hope.magic.annotation.AccessMode;
import hope.magic.annotation.HField;
import hope.magic.annotation.HMarkMagic;
import hope.magic.annotation.HMethod;

/**
 * 方案 2B：Unsafe（字段） + MethodHandle.invokeExact（方法）
 * 专用于 Android (ART VM) 以及非 HotSpot 虚拟机。
 */
@HMarkMagic(mode = AccessMode.UNSAFE_AND_METHODHANDLE)
public class AndroidAccessorSample {

	/** @see TargetObject#secretCode */
	@HField(isGetter = true)
	public static int getSecretCode(TargetObject target) {
		return 0;
	}

	/** @see TargetObject#secretCode */
	@HField(isGetter = false)
	public static void setSecretCode(TargetObject target, int value) {
	}

	/** @see TargetObject#multiply(int, int) */
	@HMethod
	public static int callMultiply(TargetObject target, int a, int b) {
		return 0;
	}

	/** @see TargetObject#staticPrivateGreet(String) */
	@HMethod
	public static String callStaticPrivateGreet(String name) {
		return null;
	}

	/** @see TargetObject#TargetObject(int, String) */
	@HMethod
	public static TargetObject newTargetObject(int secretCode, String message) {
		return null;
	}
}
