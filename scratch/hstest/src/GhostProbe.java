import nipx.LambdaAligner;

import java.lang.reflect.Method;

/**
 * 验真阳性/真阴性，按评审设计：
 *   1. **应熔断**：UpdateRef 直接持有指向桩的方法引用 —— 引用被清空，异常不外泄。
 *   2. **应静默**：中间夹一层活 lambda（桩不是 UpdateRef 直接持有的）。
 *   3. **应静默**：在 2 之上再夹 20 层递归帧（覆盖旧 16 帧上限的漏洞）。
 *   4. **透明帧判据**（回归）：被 CellPropertyRef 代理包过的回调，其栈上夹着
 *      动态代理帧 / {@code CellPropertyRef} handler 帧 / 方法句柄帧，判据必须能穿透它们，
 *      否则幽灵桩会误判为"非 UpdateRef"、熔断静默失效；同时业务帧与 {@code UpdateRef}
 *      自身绝不能透明。
 *
 * 只依赖 LambdaAligner（不编译期依赖 UpdateRef）：用反射调用
 * {@code UpdateRef.wrap(Runnable)}，因为 UpdateRef 的其它重载签名引用了 arc 的
 * Cons/Boolp，编译整个类需要 arc 在 classpath 上。
 */
public class GhostProbe {

	/** 模拟"幽灵空壳"：注入的桩调用 onOrphanInvoked 后线性返回。 */
	public static void stub() {
		LambdaAligner.onOrphanInvoked("probe.Ghost#stub()V");
	}

	static int FAILURES = 0;

	public static void main(String[] args) throws Exception {
		LambdaAligner.setOrphanPolicy(LambdaAligner.OrphanPolicy.SMART_ADAPTIVE);

		Class<?> updateRef = Class.forName("nipx.ref.UpdateRef");
		Method wrap = updateRef.getMethod("wrap", Runnable.class);

		// ── 用例 1：UpdateRef 直接持有方法引用 ──
		//
		// 期望：onOrphanInvoked 判定"由 UpdateRef 发起"后抛 NoSuchMethodError；UpdateRef 捕获该
		// LinkageError 触发熔断（清空被代理引用），异常**不再外泄**。因此可观测量是
		// "引用被清空 + run() 不抛"，而不是"run() 抛 NoSuchMethodError"（那是熔断引入前的旧语义）。
		Object direct = wrap.invoke(null, (Runnable) GhostProbe::stub);
		boolean leaked = false;
		try {
			((Runnable) direct).run();
		} catch (Throwable t) {
			leaked = true;
			System.out.println("  用例1 异常外泄（期望被熔断吞掉）: " + t);
		}
		check("1 UpdateRef 直接持有方法引用 -> 应熔断且不外泄",
			!leaked && originalIsNull(direct), true);

		// ── 用例 2：中间夹一层活 lambda（期望静默）──
		boolean threw2 = false;
		try {
			Runnable w = (Runnable) wrap.invoke(null, (Runnable) () -> GhostProbe.stub());
			w.run();
		} catch (Throwable t) {
			threw2 = isNoSuchMethod(t);
			if (!threw2) System.out.println("  用例2 非预期异常: " + t);
		}
		check("2 中间夹一层活 lambda -> 应静默", threw2, false);

		// ── 用例 3：在用例 2 之上再夹 20 层递归帧（期望静默）──
		boolean threw3 = false;
		try {
			Runnable w = (Runnable) wrap.invoke(null, (Runnable) () -> deep(20));
			w.run();
		} catch (Throwable t) {
			threw3 = isNoSuchMethod(t);
			if (!threw3) System.out.println("  用例3 非预期异常: " + t);
		}
		check("3 再夹 20 层递归帧 -> 应静默", threw3, false);

		// ── 用例 4：透明帧判据（#4 回归）──
		//
		// 被 CellPropertyRef.makeLambda 代理的回调，栈在"幽灵桩"与"UpdateRef.run*"之间会夹着：
		//   动态代理类帧、CellPropertyRef 的 handler lambda / invoke 辅助帧、方法句柄隐藏帧。
		// 这些必须判定为透明，否则 isCalledByUpdateRef 会取到 handler 帧而误判为"非 UpdateRef"，
		// 熔断在 60FPS 热路径上永不触发。
		Method isTransparent = LambdaAligner.class.getDeclaredMethod("isTransparentFrame", String.class);
		isTransparent.setAccessible(true);
		checkTransparent(isTransparent, "jdk.proxy2.$Proxy37", true);                     // JDK9+ 动态代理
		checkTransparent(isTransparent, "com.sun.proxy.$Proxy3", true);                   // JDK8 动态代理
		checkTransparent(isTransparent, "nipx.uihook.CellPropertyRef", true);             // 代理 handler / invoke
		checkTransparent(isTransparent, "java.lang.invoke.LambdaForm$MH/0x0000000801000400", true); // 方法句柄
		checkTransparent(isTransparent, "com.example.build.Foo$$Lambda/0x0000000801000400", true);
		checkTransparent(isTransparent, "nipx.ref.UpdateRef", false);                     // 真实调用者
		checkTransparent(isTransparent, "com.example.Business", false);                   // 业务帧

		System.out.println(FAILURES == 0 ? "GHOST-PROBE: ALL PASSED"
			: "GHOST-PROBE: " + FAILURES + " FAILED");
		System.exit(FAILURES == 0 ? 0 : 1);
	}

	/** 反射调用私有判据 {@code LambdaAligner.isTransparentFrame}，断言某类名是否应被穿透。 */
	static void checkTransparent(Method m, String className, boolean expected) throws Exception {
		boolean actual = (Boolean) m.invoke(null, className);
		boolean ok = actual == expected;
		if (!ok) FAILURES++;
		System.out.println((ok ? "  PASS  " : "  FAIL  ") + "4 transparent(" + className + ")="
			+ actual + ", expected=" + expected);
	}

	/** 反射调用会把异常包在 InvocationTargetException 里，需要剥一层。 */
	static boolean isNoSuchMethod(Throwable t) {
		for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
			if (c instanceof NoSuchMethodError) return true;
		}
		return false;
	}

	/** {@code WrappedRef.getOriginal()} 是否为 null —— 熔断触发后引用被清空的可观测量。 */
	static boolean originalIsNull(Object wrapped) {
		try {
			return wrapped.getClass().getMethod("getOriginal").invoke(wrapped) == null;
		} catch (Throwable t) {
			return false;
		}
	}

	static void deep(int n) {
		if (n <= 0) { GhostProbe.stub(); return; }
		deep(n - 1);
	}

	static void check(String name, boolean actual, boolean expected) {
		boolean ok = actual == expected;
		if (!ok) FAILURES++;
		System.out.println((ok ? "  PASS  " : "  FAIL  ") + name
			+ "  (threw=" + actual + ", expected=" + expected + ")");
	}
}
