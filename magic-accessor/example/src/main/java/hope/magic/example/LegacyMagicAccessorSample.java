package hope.magic.example;

import hope.magic.annotation.AccessMode;
import hope.magic.annotation.HField;
import hope.magic.annotation.HMarkMagic;
import hope.magic.annotation.HMethod;

/**
 * 方案 1：MagicAccessorImpl 字节码方案（适用于 JDK <= 21）。
 */
@HMarkMagic(mode = AccessMode.MAGIC_ACCESSOR)
public class LegacyMagicAccessorSample {

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

	/** @see TargetObject#TargetObject(int, String) */
	@HMethod
	public static TargetObject newTargetObject(int secretCode, String message) {
		return null;
	}
}
