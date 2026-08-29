package hope.magic.example;

import hope.magic.runtime.Magic;

public class Main {
	public static void main(String[] args) {
		// 1. 初始化 Magic 运行时（加载特权类）
		Magic.install();

		TargetObject obj = new TargetObject();
		System.out.println("--- 初始状态 ---");
		System.out.println("obj.getSecretCode() = " + obj.getSecretCode());
		System.out.println("obj.getMessage() = " + obj.getMessage());

		// 2. 通过生成的 Accessor 直接读取私有字段
		System.out.println("\n--- Magic 读取私有字段 ---");
		int secret = MagicAccessorSample.getSecretCode(obj);
		String msg = MagicAccessorSample.getMessage(obj);
		System.out.println("MagicAccessorSample.getSecretCode(obj) = " + secret);
		System.out.println("MagicAccessorSample.getMessage(obj) = " + msg);

		// 3. 通过生成的 Accessor 直接修改私有字段
		System.out.println("\n--- Magic 修改私有字段 ---");
		MagicAccessorSample.setSecretCode(obj, 99999);
		MagicAccessorSample.setMessage(obj, "Modified via MagicAccessor!");
		System.out.println("修改后 obj.getSecretCode() = " + obj.getSecretCode());
		System.out.println("修改后 obj.getMessage() = " + obj.getMessage());

		// 4. 通过生成的 Accessor 直接调用私有方法
		System.out.println("\n--- Magic 调用私有方法 ---");
		int product = MagicAccessorSample.callMultiply(obj, 6, 7);
		System.out.println("MagicAccessorSample.callMultiply(obj, 6, 7) = " + product);

		String greet = MagicAccessorSample.callStaticPrivateGreet("Developer");
		System.out.println("MagicAccessorSample.callStaticPrivateGreet(\"Developer\") = " + greet);

		System.out.println("\n全部测试成功！");
	}
}
