package hope.magic.example;

import hope.magic.annotation.AccessMode;
import hope.magic.annotation.HField;
import hope.magic.annotation.HMarkMagic;
import hope.magic.annotation.HMethod;

/**
 * AUTO 模式访问器：在 Desktop JVM 上自动选用 UNSAFE_AND_LINKTO，在 Android (targetVersion=8) 上自动选用 UNSAFE_AND_METHODHANDLE。
 */
@HMarkMagic(mode = AccessMode.AUTO)
public class AutoAccessorSample {

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
