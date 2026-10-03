import nipx.LambdaAligner;

import java.lang.reflect.Method;

/**
 * 验真阳性/真阴性，按评审设计：
 *   1. **应抛**：UpdateRef 直接持有指向桩的方法引用。
 *   2. **应静默**：中间夹一层活 lambda（桩不是 UpdateRef 直接持有的）。
 *   3. **应静默**：在 2 之上再夹 20 层递归帧（覆盖旧 16 帧上限的漏洞）。
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

		// ── 用例 1：UpdateRef 直接持有方法引用（期望抛 NoSuchMethodError）──
		boolean threw = false;
		try {
			Runnable direct = (Runnable) wrap.invoke(null, (Runnable) GhostProbe::stub);
			direct.run();
		} catch (Throwable t) {
			threw = isNoSuchMethod(t);
			if (!threw) System.out.println("  用例1 非预期异常: " + t);
		}
		check("1 UpdateRef 直接持有方法引用 -> 应抛", threw, true);

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

		System.out.println(FAILURES == 0 ? "GHOST-PROBE: ALL PASSED"
			: "GHOST-PROBE: " + FAILURES + " FAILED");
		System.exit(FAILURES == 0 ? 0 : 1);
	}

	/** 反射调用会把异常包在 InvocationTargetException 里，需要剥一层。 */
	static boolean isNoSuchMethod(Throwable t) {
		for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
			if (c instanceof NoSuchMethodError) return true;
		}
		return false;
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
