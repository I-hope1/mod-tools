package hope.magic.example;

import hope.magic.annotation.HField;
import hope.magic.annotation.HMarkMagic;
import hope.magic.annotation.HMethod;

@HMarkMagic
public class MagicAccessorSample {

	/** @see TargetObject#secretCode */
	@HField(isGetter = true)
	public static int getSecretCode(TargetObject target) {
		return 0;
	}

	/** @see TargetObject#secretCode */
	@HField(isGetter = false)
	public static void setSecretCode(TargetObject target, int value) {
	}

	/** @see TargetObject#message */
	@HField(isGetter = true)
	public static String getMessage(TargetObject target) {
		return null;
	}

	/** @see TargetObject#message */
	@HField(isGetter = false)
	public static void setMessage(TargetObject target, String value) {
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
}
