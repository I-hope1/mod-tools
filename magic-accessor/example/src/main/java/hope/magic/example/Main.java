package hope.magic.example;

import hope.magic.runtime.Magic;

public class Main {
	public static void main(String[] args) throws ClassNotFoundException {
		// 1. 初始化 Magic 运行时（自动定义 Bootstrap 特权类与加载 linkToXX 支撑类）
		Magic.install();

		// 测试FieldUtils是否存在
		System.out.println(Class.forName("hope_android.FieldUtils"));

		TargetObject obj = new TargetObject();
		System.out.println("====== 初始对象状态 ======");
		System.out.println("obj.getSecretCode() = " + obj.getSecretCode());
		System.out.println("obj.getMessage() = " + obj.getMessage());

		// ======================== 方案 2A: Unsafe + invokedynamic (indy 动态调用点) ========================
		System.out.println("\n====== 测试方案 2A: Unsafe + invokedynamic 模式 (JVM 原生 indy 极速直调) ======");
		int secretLinkTo = MagicAccessorSample.getSecretCode(obj);
		String msgLinkTo = MagicAccessorSample.getMessage(obj);
		System.out.println("[Unsafe Getter] secretCode = " + secretLinkTo);
		System.out.println("[Unsafe Getter] message = " + msgLinkTo);

		MagicAccessorSample.setSecretCode(obj, 88888);
		MagicAccessorSample.setMessage(obj, "Updated by Unsafe Accessor!");
		System.out.println("[Unsafe Setter] 新的 secretCode = " + obj.getSecretCode());
		System.out.println("[Unsafe Setter] 新的 message = " + obj.getMessage());

		int productLinkTo = MagicAccessorSample.callMultiply(obj, 9, 9);
		System.out.println("[invokedynamic special] callMultiply(obj, 9, 9) = " + productLinkTo);

		String greetLinkTo = MagicAccessorSample.callStaticPrivateGreet("indy Static User");
		System.out.println("[invokedynamic static] callStaticPrivateGreet = " + greetLinkTo);

		// ======================== 方案 2B: Unsafe + MethodHandle (Android ART / 跨平台通用) ========================
		System.out.println("\n====== 测试方案 2B: Unsafe + MethodHandle 模式 (Android ART / 跨平台兼容) ======");
		int secretAndroid = AndroidAccessorSample.getSecretCode(obj);
		System.out.println("[Android Unsafe Getter] secretCode = " + secretAndroid);

		AndroidAccessorSample.setSecretCode(obj, 66666);
		System.out.println("[Android Unsafe Setter] 新的 secretCode = " + obj.getSecretCode());

		int productAndroid = AndroidAccessorSample.callMultiply(obj, 5, 5);
		System.out.println("[MethodHandle invokeExact] callMultiply(obj, 5, 5) = " + productAndroid);

		String greetAndroid = AndroidAccessorSample.callStaticPrivateGreet("Android User");
		System.out.println("[MethodHandle invokeExact] callStaticPrivateGreet = " + greetAndroid);

		// ======================== 方案 1: MagicAccessorImpl (经典特权方案) ========================
		try {
			System.out.println("\n====== 测试方案 1: MagicAccessorImpl 模式 (JDK <= 21) ======");
			int legacySecret = LegacyMagicAccessorSample.getSecretCode(obj);
			System.out.println("[MagicAccessor Getter] secretCode = " + legacySecret);

			LegacyMagicAccessorSample.setSecretCode(obj, 77777);
			System.out.println("[MagicAccessor Setter] 新的 secretCode = " + obj.getSecretCode());

			int legacyProduct = LegacyMagicAccessorSample.callMultiply(obj, 8, 8);
			System.out.println("[MagicAccessor Method] callMultiply(obj, 8, 8) = " + legacyProduct);
		} catch (Throwable t) {
			System.out.println("[MagicAccessor] 当前 JDK 环境不适用 MagicAccessorImpl: " + t.getMessage());
		}

		System.out.println("\n====== 全部方案测试完毕 ======");
	}
}
