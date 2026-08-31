package hope.magic.example;

import hope.magic.annotation.AccessMode;
import hope.magic.annotation.HField;
import hope.magic.annotation.HMarkMagic;
import hope.magic.annotation.HMethod;

/**
 * 方案 2A：Unsafe（字段访问）+ MagicBridge linkToXX（编译期收集签名生成 MagicBridge，运行期注入 java.lang.invoke）
 * 零预热、零反射、C2 JIT 直通硬件机器指令。
 */
@HMarkMagic(mode = AccessMode.UNSAFE_AND_LINKTO)
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

	/** @see TargetObject#TargetObject(int, String) */
	@HMethod
	public static TargetObject newTargetObject(int secretCode, String message) {
		return null;
	}
}
