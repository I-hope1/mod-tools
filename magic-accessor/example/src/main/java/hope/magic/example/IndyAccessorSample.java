package hope.magic.example;

import hope.magic.annotation.AccessMode;
import hope.magic.annotation.HField;
import hope.magic.annotation.HMarkMagic;
import hope.magic.annotation.HMethod;

/**
 * 方案 2B：Unsafe（字段访问）+ invokedynamic（方法访问）
 * 基于 JVM 标准 indy 指令与 ConstantCallSite 绑定。
 */
@HMarkMagic(mode = AccessMode.UNSAFE_AND_INDY)
public class IndyAccessorSample {

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
}
