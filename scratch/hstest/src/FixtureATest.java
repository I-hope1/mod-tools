import nipx.LambdaAligner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 夹具 A 期望版断言（JUnit 化）：叶子描述符变化时，中层应当保名，不被 hasUnmatchedChild 连坐幽灵化。
 *
 * <p>期望验收标准（一律按 name+desc 比较）：<br>
 * 1. 旧中层在最终类里必须是活方法（!isGhost）；<br>
 * 2. 产物活中层必须沿用旧中层名与描述符；<br>
 * 3. 旧叶子因描述符变化无候选，必须作为幽灵兜底；<br>
 * 4. 幽灵方法总数恰好为 1（仅旧叶子，中层不被连坐）。</p>
 * <p>矩阵：JDK 8/17/21 × 正反序。</p>
 */
public class FixtureATest {

	static ClassNode parse(byte[] b) {
		ClassNode cn = new ClassNode();
		new ClassReader(b).accept(cn, 0);
		return cn;
	}

	static boolean isGhost(MethodNode mn) {
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof MethodInsnNode m && m.owner.equals("nipx/LambdaAligner")
			    && m.name.equals("onOrphanInvoked")) return true;
		}
		return false;
	}

	static Set<String> getChildLambdaKeys(ClassNode cn, MethodNode mn) {
		Set<String> kids = new LinkedHashSet<>();
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof InvokeDynamicInsnNode indy) {
				if (indy.bsmArgs != null) {
					for (Object arg : indy.bsmArgs) {
						if (arg instanceof Handle h) {
							if (h.getOwner().equals(cn.name) && h.getName().startsWith("lambda$")) {
								kids.add(h.getName() + h.getDesc());
							}
						}
					}
				}
			}
		}
		return kids;
	}

	static class StructureInfo {
		MethodNode leaf;
		MethodNode middle;

		String leafKey() { return leaf.name + leaf.desc; }
		String middleKey() { return middle.name + middle.desc; }
	}

	static StructureInfo locate(ClassNode cn) {
		StructureInfo info = new StructureInfo();
		List<MethodNode> lambdas = new ArrayList<>();
		Map<MethodNode, Set<String>> childrenMap = new HashMap<>();

		for (MethodNode mn : cn.methods) {
			if (mn.name.startsWith("lambda$")) {
				lambdas.add(mn);
				childrenMap.put(mn, getChildLambdaKeys(cn, mn));
			}
		}

		for (MethodNode mn : lambdas) {
			Set<String> kids = childrenMap.get(mn);
			if (kids.isEmpty()) {
				info.leaf = mn;
				break;
			}
		}

		if (info.leaf != null) {
			String leafKey = info.leaf.name + info.leaf.desc;
			for (MethodNode mn : lambdas) {
				Set<String> kids = childrenMap.get(mn);
				if (kids.contains(leafKey)) {
					info.middle = mn;
					break;
				}
			}
		}

		return info;
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

	static void testOne(String label, String v1Path, String v2Path, boolean reverse) throws Exception {
		byte[] v1 = Files.readAllBytes(findPath(v1Path));
		byte[] v2 = Files.readAllBytes(findPath(v2Path));

		ClassNode cn1 = parse(v1);
		ClassNode cn2 = parse(v2);

		StructureInfo oldStruct = locate(cn1);
		StructureInfo newStruct = locate(cn2);

		if (oldStruct.middle == null || oldStruct.leaf == null ||
		    newStruct.middle == null || newStruct.leaf == null) {
			throw new IllegalStateException("未能从夹具中按结构定位出中层与叶子: " + label);
		}

		byte[] out = align(v1, v2, reverse);
		ClassNode outCn = parse(out);

		MethodNode alignedOldMiddle = null;
		MethodNode alignedOldLeaf = null;
		int ghostCount = 0;

		for (MethodNode mn : outCn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			if (isGhost(mn)) ghostCount++;
			if ((mn.name + mn.desc).equals(oldStruct.middleKey())) {
				alignedOldMiddle = mn;
			}
			if ((mn.name + mn.desc).equals(oldStruct.leafKey())) {
				alignedOldLeaf = mn;
			}
		}

		String orderTag = reverse ? " [反序]" : " [正序]";
		System.out.println("== " + label + orderTag + " ==");

		MethodNode liveLeaf = null;
		for (MethodNode mn : outCn.methods) {
			if (mn.name.startsWith("lambda$") && !isGhost(mn) && getChildLambdaKeys(outCn, mn).isEmpty()) {
				liveLeaf = mn;
				break;
			}
		}
		MethodNode liveMiddle = null;
		if (liveLeaf != null) {
			String liveLeafKey = liveLeaf.name + liveLeaf.desc;
			for (MethodNode mn : outCn.methods) {
				if (mn.name.startsWith("lambda$") && !isGhost(mn)) {
					if (getChildLambdaKeys(outCn, mn).contains(liveLeafKey)) {
						liveMiddle = mn;
						break;
					}
				}
			}
		}
		String liveMidKey = liveMiddle != null ? (liveMiddle.name + liveMiddle.desc) : "未找到";
		System.out.println("   [产物 out] 结构定位活中层: " + liveMidKey);

		// 期望断言 1：旧中层方法在产物中必须存在且为活方法（不被幽灵化）
		assertTrue(alignedOldMiddle != null && !isGhost(alignedOldMiddle),
			label + orderTag + " 旧中层 " + oldStruct.middleKey() + " 必须作为活方法保留（当前状态: "
				+ (alignedOldMiddle == null ? "缺失" : (isGhost(alignedOldMiddle) ? "被幽灵化" : "活方法")) + "）");

		// 期望断言 2：产物中的活中层必须沿用旧中层名与描述符（未被赋予 fresh name）
		assertTrue(liveMiddle != null && (liveMiddle.name + liveMiddle.desc).equals(oldStruct.middleKey()),
			label + orderTag + " 产物活中层必须沿用旧中层名 " + oldStruct.middleKey() + "（实际拿了: " + liveMidKey + "）");

		// 期望断言 3：旧叶子由于描述符变化且无候选，必须被幽灵化以保护老 CallSite
		assertTrue(alignedOldLeaf != null && isGhost(alignedOldLeaf),
			label + orderTag + " 旧叶子 " + oldStruct.leafKey() + " 必须作为幽灵方法兜底");

		// 期望断言 4：幽灵数量恰好为 1（仅叶子，中层不连坐）
		assertEquals(1, ghostCount,
			label + orderTag + " 幽灵方法总数应恰为 1（实际=" + ghostCount + "）");
		System.out.println();
	}

	@Test
	void middleKeepsNameWhenLeafDescriptorChanges(@TempDir Path tmp) throws Exception {
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
			testOne("JDK" + major, v1, v2, false);
			testOne("JDK" + major, v1, v2, true);
		}
	}
}
