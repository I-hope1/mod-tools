import nipx.LambdaAligner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 名字键索引缺陷独立断言（JUnit 化）。
 *
 * <p>场景：第二轮对齐（对齐结果作为旧类，再次对齐同一份新类）。旧类中同时存在活叶子与上一轮注入的
 * 幽灵叶子（同名不同描述符）时，名字索引必须保留活方法；因此第二轮应在 Step 1 就完成配对
 * （step1Pairs == 2，passAPairs == 0）。矩阵覆盖 JDK 8/17/21 × 正反序。</p>
 */
public class NameIndexTest {

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
		assertNotNull(out1, label + orderTag + " 第一轮 align 返回非空");

		// 第二轮：V2产物 -> V2（检测同名活/幽灵对共存下的索引行为）
		LambdaAligner.LAST_STATS = null;
		byte[] out2 = align(out1, v2, reverse);
		assertNotNull(out2, label + orderTag + " 第二轮 align 返回非空");

		LambdaAligner.AlignmentStats stats = LambdaAligner.LAST_STATS;
		assertNotNull(stats, label + orderTag + " 第二轮配对统计非空（已正常写入 LAST_STATS）");

		System.out.println("   第二轮统计: " + stats);
		if (stats != null) {
			assertEquals(2, stats.step1Pairs,
				label + orderTag + " 第二轮中层与叶子必须全部通过 Step 1 证据匹配（实际=" + stats.step1Pairs + "）");
			assertEquals(0, stats.passAPairs,
				label + orderTag + " 第二轮不应回退到 A 趟降级兜底（实际=" + stats.passAPairs + "）");
		}
		System.out.println();
	}

	@Test
	void secondRoundUsesNameIndexWithoutGhostCollision(@TempDir Path tmp) throws Exception {
		for (int major : new int[] { 8, 17, 21 }) {
			String javac = HstestFixtures.javac(major);
			Path d1 = tmp.resolve("fa" + major + "v1");
			Path d2 = tmp.resolve("fa" + major + "v2");
			HstestFixtures.compile(javac, d1,
				HstestFixtures.find("compA/Time.java"), HstestFixtures.find("compA/v1/FixtureA.java"));
			HstestFixtures.compile(javac, d2,
				HstestFixtures.find("compA/Time.java"), HstestFixtures.find("compA/v2/FixtureA.java"));

			String v1 = d1.resolve("FixtureA.class").toString();
			String v2 = d2.resolve("FixtureA.class").toString();
			testJdk("JDK" + major, v1, v2, false);
			testJdk("JDK" + major, v1, v2, true);
		}
	}
}
