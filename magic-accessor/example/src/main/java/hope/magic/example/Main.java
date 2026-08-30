package hope.magic.example;

import hope.magic.runtime.Magic;
import java.util.*;

public class Main {
	interface SchemeTask {
		String name();
		void runFirst(TargetObject obj);
		void runSecond(TargetObject obj);
	}

	public static void main(String[] args) throws Throwable {
		TargetObject obj = new TargetObject();
		System.out.println("====== 初始对象状态 ======");
		System.out.println("obj.getSecretCode() = " + obj.getSecretCode());
		System.out.println("obj.getMessage() = " + obj.getMessage());

		List<SchemeTask> tasks = new ArrayList<>();

		// 方案 2A: Unsafe + MagicBridge linkToXX
		tasks.add(new SchemeTask() {
			@Override
			public String name() { return "方案 2A (Unsafe + linkToXX)"; }
			@Override
			public void runFirst(TargetObject obj) {
				int    s = MagicAccessorSample.getSecretCode(obj);
				String m = MagicAccessorSample.getMessage(obj);
				MagicAccessorSample.setSecretCode(obj, 88888);
				MagicAccessorSample.setMessage(obj, "Updated by 2A");
				int    p = MagicAccessorSample.callMultiply(obj, 9, 9);
				String g = MagicAccessorSample.callStaticPrivateGreet("linkTo User");
				System.out.println("  [2A 结果] secretCode=" + s + ", message=" + m + ", multiply=" + p + ", greet=" + g);
			}
			@Override
			public void runSecond(TargetObject obj) {
				MagicAccessorSample.getSecretCode(obj);
				MagicAccessorSample.getMessage(obj);
				MagicAccessorSample.setSecretCode(obj, 99999);
				MagicAccessorSample.setMessage(obj, "2A 2nd");
				MagicAccessorSample.callMultiply(obj, 3, 3);
				MagicAccessorSample.callStaticPrivateGreet("User 2");
			}
		});

		// 方案 2B: Unsafe + invokedynamic (indy)
		tasks.add(new SchemeTask() {
			@Override
			public String name() { return "方案 2B (Unsafe + invokedynamic)"; }
			@Override
			public void runFirst(TargetObject obj) {
				int s = IndyAccessorSample.getSecretCode(obj);
				IndyAccessorSample.setSecretCode(obj, 55555);
				int    p = IndyAccessorSample.callMultiply(obj, 7, 7);
				String g = IndyAccessorSample.callStaticPrivateGreet("indy User");
				System.out.println("  [2B 结果] secretCode=" + s + ", multiply=" + p + ", greet=" + g);
			}
			@Override
			public void runSecond(TargetObject obj) {
				IndyAccessorSample.getSecretCode(obj);
				IndyAccessorSample.setSecretCode(obj, 99999);
				IndyAccessorSample.callMultiply(obj, 3, 3);
				IndyAccessorSample.callStaticPrivateGreet("User 2");
			}
		});

		// 方案 2C: Unsafe + MethodHandle
		tasks.add(new SchemeTask() {
			@Override
			public String name() { return "方案 2C (Unsafe + MethodHandle)"; }
			@Override
			public void runFirst(TargetObject obj) {
				int s = AndroidAccessorSample.getSecretCode(obj);
				AndroidAccessorSample.setSecretCode(obj, 66666);
				int    p = AndroidAccessorSample.callMultiply(obj, 5, 5);
				String g = AndroidAccessorSample.callStaticPrivateGreet("Android User");
				System.out.println("  [2C 结果] secretCode=" + s + ", multiply=" + p + ", greet=" + g);
			}
			@Override
			public void runSecond(TargetObject obj) {
				AndroidAccessorSample.getSecretCode(obj);
				AndroidAccessorSample.setSecretCode(obj, 99999);
				AndroidAccessorSample.callMultiply(obj, 3, 3);
				AndroidAccessorSample.callStaticPrivateGreet("User 2");
			}
		});

		// 方案 1: MagicAccessorImpl (JDK <= 21)
		tasks.add(new SchemeTask() {
			@Override
			public String name() { return "方案 1 (MagicAccessorImpl)"; }
			@Override
			public void runFirst(TargetObject obj) {
				try {
					Magic.install();
					int s = LegacyMagicAccessorSample.getSecretCode(obj);
					LegacyMagicAccessorSample.setSecretCode(obj, 77777);
					int p = LegacyMagicAccessorSample.callMultiply(obj, 8, 8);
					System.out.println("  [1 结果] secretCode=" + s + ", multiply=" + p);
				} catch (Throwable t) {
					System.out.println("  [1 结果] 当前 JDK 环境不适用: " + t.getMessage());
				}
			}
			@Override
			public void runSecond(TargetObject obj) {
				try {
					LegacyMagicAccessorSample.getSecretCode(obj);
					LegacyMagicAccessorSample.setSecretCode(obj, 99999);
					LegacyMagicAccessorSample.callMultiply(obj, 3, 3);
				} catch (Throwable ignored) { }
			}
		});

		// 🎲 随机打乱执行顺序
		Collections.shuffle(tasks);
		System.out.println("\n🎲 本次随机执行顺序为: ");
		for (int i = 0; i < tasks.size(); i++) {
			System.out.println("  " + (i + 1) + ". " + tasks.get(i).name());
		}

		System.out.println("\n====== 开始执行随机性能评测 ======");
		for (int i = 0; i < tasks.size(); i++) {
			SchemeTask task = tasks.get(i);
			System.out.println("\n[" + (i + 1) + "/" + tasks.size() + "] 测试 " + task.name() + ":");

			long t1Start = System.nanoTime();
			task.runFirst(obj);
			long t1 = System.nanoTime() - t1Start;

			long t2Start = System.nanoTime();
			task.runSecond(obj);
			long t2 = System.nanoTime() - t2Start;

			System.out.printf("  第 1 次未预热首调耗时: %,12d ns (%.3f ms)%n", t1, t1 / 1_000_000.0);
			System.out.printf("  第 2 次直接调用耗时  : %,12d ns (%.3f µs)%n", t2, t2 / 1_000.0);
		}

		System.out.println("\n====== 全部方案随机测试完毕 ======");
	}
}
