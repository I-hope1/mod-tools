package hope.magic.example;

import hope.magic.runtime.Magic;

public class Main {
	public static void main(String[] args) {
		TargetObject obj = new TargetObject();
		System.out.println("====== 初始对象状态 ======");
		System.out.println("obj.getSecretCode() = " + obj.getSecretCode());
		System.out.println("obj.getMessage() = " + obj.getMessage());

		// ======================== 方案 2A: Unsafe + MagicBridge linkToXX (编译期生成 + 运行期注入) ========================
		System.out.println("\n====== 测试方案 2A: Unsafe + MagicBridge linkToXX 模式 (编译期签名收集 + 运行期注入 java.lang.invoke) ======");
		int secretLinkTo = MagicAccessorSample.getSecretCode(obj);
		String msgLinkTo = MagicAccessorSample.getMessage(obj);
		System.out.println("[Unsafe Getter] secretCode = " + secretLinkTo);
		System.out.println("[Unsafe Getter] message = " + msgLinkTo);

		MagicAccessorSample.setSecretCode(obj, 88888);
		MagicAccessorSample.setMessage(obj, "Updated by Unsafe Accessor!");
		System.out.println("[Unsafe Setter] 新的 secretCode = " + obj.getSecretCode());
		System.out.println("[Unsafe Setter] 新的 message = " + obj.getMessage());

		int productLinkTo = MagicAccessorSample.callMultiply(obj, 9, 9);
		System.out.println("[MagicBridge linkToSpecial] callMultiply(obj, 9, 9) = " + productLinkTo);

		String greetLinkTo = MagicAccessorSample.callStaticPrivateGreet("linkTo User");
		System.out.println("[MagicBridge linkToStatic] callStaticPrivateGreet = " + greetLinkTo);

		// ======================== 方案 2B: Unsafe + invokedynamic (indy 动态调用点) ========================
		System.out.println("\n====== 测试方案 2B: Unsafe + invokedynamic 模式 (JVM 原生 indy 极速直调) ======");
		int secretIndy = IndyAccessorSample.getSecretCode(obj);
		System.out.println("[Indy Unsafe Getter] secretCode = " + secretIndy);

		IndyAccessorSample.setSecretCode(obj, 55555);
		System.out.println("[Indy Unsafe Setter] 新的 secretCode = " + obj.getSecretCode());

		int productIndy = IndyAccessorSample.callMultiply(obj, 7, 7);
		System.out.println("[invokedynamic special] callMultiply(obj, 7, 7) = " + productIndy);

		String greetIndy = IndyAccessorSample.callStaticPrivateGreet("indy User");
		System.out.println("[invokedynamic static] callStaticPrivateGreet = " + greetIndy);

		// ======================== 方案 2C: Unsafe + MethodHandle (Android ART / 跨平台通用) ========================
		System.out.println("\n====== 测试方案 2C: Unsafe + MethodHandle 模式 (Android ART / 跨平台兼容) ======");
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
			Magic.install();
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
