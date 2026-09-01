package hope.magic.example;

import hope.magic.js.compiler.JSCompiler;
import hope.magic.js.runtime.*;

import java.util.*;

public class Main {
	interface SchemeTask {
		String name();
		void runFirst(TargetObject obj);
		void runSecond(TargetObject obj);
	}

	public static void main(String[] args) throws Throwable {
		TargetObject obj = new TargetObject();

		JSScript script = JSCompiler.compile("""
		 var arr = [1, 2, 3];

arr[3] = 4;

arr.length;    // 3.0，而不是 4.0
		 """);
		JSContext cx = new JSContext();
		cx.set("target", obj);
		System.out.println(script.run(cx));
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
				int          p       = MagicAccessorSample.callMultiply(obj, 9, 9);
				String       g       = MagicAccessorSample.callStaticPrivateGreet("linkTo User");
				TargetObject created = MagicAccessorSample.newTargetObject(2026, "Created via 2A linkTo <init>");
				System.out.println("  [2A 结果] secretCode=" + s + ", message=" + m + ", multiply=" + p + ", greet=" + g);
				System.out.println("  [2A 私有构造测试] created: code=" + created.getSecretCode() + ", msg=" + created.getMessage());
			}
			@Override
			public void runSecond(TargetObject obj) {
				MagicAccessorSample.getSecretCode(obj);
				MagicAccessorSample.getMessage(obj);
				MagicAccessorSample.setSecretCode(obj, 99999);
				MagicAccessorSample.setMessage(obj, "2A 2nd");
				MagicAccessorSample.callMultiply(obj, 3, 3);
				MagicAccessorSample.callStaticPrivateGreet("User 2");
				MagicAccessorSample.newTargetObject(111, "2nd");
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
				int          p       = IndyAccessorSample.callMultiply(obj, 7, 7);
				String       g       = IndyAccessorSample.callStaticPrivateGreet("indy User");
				TargetObject created = IndyAccessorSample.newTargetObject(2026, "Created via 2B indy <init>");
				System.out.println("  [2B 结果] secretCode=" + s + ", multiply=" + p + ", greet=" + g);
				System.out.println("  [2B 私有构造测试] created: code=" + created.getSecretCode() + ", msg=" + created.getMessage());
			}
			@Override
			public void runSecond(TargetObject obj) {
				IndyAccessorSample.getSecretCode(obj);
				IndyAccessorSample.setSecretCode(obj, 99999);
				IndyAccessorSample.callMultiply(obj, 3, 3);
				IndyAccessorSample.callStaticPrivateGreet("User 2");
				IndyAccessorSample.newTargetObject(111, "2nd");
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
				int          p       = AndroidAccessorSample.callMultiply(obj, 5, 5);
				String       g       = AndroidAccessorSample.callStaticPrivateGreet("Android User");
				TargetObject created = AndroidAccessorSample.newTargetObject(2026, "Created via 2C MH <init>");
				System.out.println("  [2C 结果] secretCode=" + s + ", multiply=" + p + ", greet=" + g);
				System.out.println("  [2C 私有构造测试] created: code=" + created.getSecretCode() + ", msg=" + created.getMessage());
			}
			@Override
			public void runSecond(TargetObject obj) {
				AndroidAccessorSample.getSecretCode(obj);
				AndroidAccessorSample.setSecretCode(obj, 99999);
				AndroidAccessorSample.callMultiply(obj, 3, 3);
				AndroidAccessorSample.callStaticPrivateGreet("User 2");
				AndroidAccessorSample.newTargetObject(111, "2nd");
			}
		});

		// AUTO 智能自适应模式 (Desktop -> linkToXX / Android -> MethodHandle)
		tasks.add(new SchemeTask() {
			@Override
			public String name() { return "AUTO 智能模式 (自适应宿主环境)"; }
			@Override
			public void runFirst(TargetObject obj) {
				int s = AutoAccessorSample.getSecretCode(obj);
				AutoAccessorSample.setSecretCode(obj, 10101);
				int          p       = AutoAccessorSample.callMultiply(obj, 4, 4);
				String       g       = AutoAccessorSample.callStaticPrivateGreet("Auto User");
				TargetObject created = AutoAccessorSample.newTargetObject(2026, "Created via AUTO <init>");
				System.out.println("  [AUTO 结果] secretCode=" + s + ", multiply=" + p + ", greet=" + g);
				System.out.println("  [AUTO 私有构造测试] created: code=" + created.getSecretCode() + ", msg=" + created.getMessage());
			}
			@Override
			public void runSecond(TargetObject obj) {
				AutoAccessorSample.getSecretCode(obj);
				AutoAccessorSample.setSecretCode(obj, 99999);
				AutoAccessorSample.callMultiply(obj, 3, 3);
				AutoAccessorSample.callStaticPrivateGreet("User 2");
				AutoAccessorSample.newTargetObject(111, "2nd");
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
