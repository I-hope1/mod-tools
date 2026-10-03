import nipx.LambdaAligner;

import java.nio.file.*;

/**
 * 名字键索引缺陷独立断言（Name-Keyed Index Defect Test）。
 *
 * <p>场景：第二轮对齐（对齐结果作为旧类，再次对齐同一份新类）。
 * 此时旧类中同时存在：
 *   - 活叶子（例如 JDK8 的 lambda$null$0(II)V 或 JDK17/21 的 lambda$build$0(II)V）
 *   - 上一轮注入的幽灵叶子（同名不同描述符，例如 (I)V）
 *
 * <p>期望行为：
 * 旧类名字索引（oldNameIndex）在同名共存时，必须保留活方法而不是被后追加的幽灵覆盖。
 * 活中层的子引用指向活叶子，语义指纹（semanticHash）正确折入叶子，
 * 与新类中层的语义指纹一致，因此在 Step 1 即可完成配对（step1Pairs == 2，passAPairs == 0）。
 *
 * <p>矩阵覆盖：
 * 覆盖 JDK 8 / 17 / 21 三套真实编译产物，且每套测试正序与反序（TEST_REVERSE_GROUP_ORDER）。
 * 每次调用前强制重置 LAST_STATS = null，并断言非空，防止残留假绿。</p>
 */
public class NameIndexTest {

	static int passed = 0, failed = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   PASS  " : "   FAIL  ") + msg);
		if (ok) passed++; else failed++;
	}

	static byte[] align(byte[] o, byte[] n, boolean reverse) {
		try {
			LambdaAligner.TEST_REVERSE_GROUP_ORDER = reverse;
			return LambdaAligner.align(o, n);
		} finally {
			LambdaAligner.TEST_REVERSE_GROUP_ORDER = false;
		}
	}

	static Path findPath(String rel) {
		Path p = Paths.get(rel);
		if (Files.exists(p)) return p;
		Path p2 = Paths.get("scratch/hstest", rel);
		if (Files.exists(p2)) return p2;
		return p;
	}

	static void testJdk(String label, String v1Path, String v2Path, boolean reverse) throws Exception {
		byte[] v1 = Files.readAllBytes(findPath(v1Path));
		byte[] v2 = Files.readAllBytes(findPath(v2Path));

		String orderTag = reverse ? " [反序]" : " [正序]";
		System.out.println("== " + label + orderTag + " ==");

		// 第一轮：V1 -> V2
		LambdaAligner.LAST_STATS = null;
		byte[] out1 = align(v1, v2, reverse);
		check(out1 != null, label + orderTag + " 第一轮 align 返回非空");

		// 第二轮：V2产物 -> V2（检测同名活/幽灵对共存下的索引行为）
		LambdaAligner.LAST_STATS = null;
		byte[] out2 = align(out1, v2, reverse);
		check(out2 != null, label + orderTag + " 第二轮 align 返回非空");

		LambdaAligner.AlignmentStats stats = LambdaAligner.LAST_STATS;
		check(stats != null, label + orderTag + " 第二轮配对统计非空（已正常写入 LAST_STATS）");

		if (stats != null) {
			System.out.println("   第二轮统计: " + stats);
			// 核心断言 1：第二轮中层与叶子都必须走 Step 1 证据匹配
			check(stats.step1Pairs == 2,
				label + orderTag + " 第二轮中层与叶子必须全部通过 Step 1 证据匹配（期望 step1=2，实际=" + stats.step1Pairs + "）");

			// 核心断言 2：第二轮不应发生 A 趟降级兜底
			check(stats.passAPairs == 0,
				label + orderTag + " 第二轮不应回退到 A 趟降级兜底（期望 passA=0，实际=" + stats.passAPairs + "）");
		}
		System.out.println();
	}

	public static void main(String[] args) throws Exception {
		System.out.println("=== 名字键索引独立断言验证（三 JDK 矩阵 + 正反序）===");

		// 1. JDK 8 矩阵
		testJdk("JDK8", "fx/fa8v1/FixtureA.class", "fx/fa8v2/FixtureA.class", false);
		testJdk("JDK8", "fx/fa8v1/FixtureA.class", "fx/fa8v2/FixtureA.class", true);

		// 2. JDK 17 矩阵
		testJdk("JDK17", "fx/fa17v1/FixtureA.class", "fx/fa17v2/FixtureA.class", false);
		testJdk("JDK17", "fx/fa17v1/FixtureA.class", "fx/fa17v2/FixtureA.class", true);

		// 3. JDK 21 矩阵
		testJdk("JDK21", "fx/fa21v1/FixtureA.class", "fx/fa21v2/FixtureA.class", false);
		testJdk("JDK21", "fx/fa21v1/FixtureA.class", "fx/fa21v2/FixtureA.class", true);

		System.out.println("----------------------------------------");
		System.out.println("通过 " + passed + " 条；失败 " + failed + " 条");
		System.out.println(failed == 0 ? "NAME-INDEX ASSERTIONS OK" : (failed + " FAILED"));
		if (failed != 0) System.exit(1);
	}
}
