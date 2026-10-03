import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 夹具 A 已知限制钉住测试（Fixture A KNOWN LIMITATION Test）。
 *
 * <p>场景：叶子 lambda 描述符变化（多捕获局部变量），但中层/外层方法体、形状、深度均未变化。
 *
 * <p>已知缺陷（未实现 settled 机制）：
 * 子 lambda 因描述符不匹配在各趟中始终未配对（matched == false），
 * 父 lambda 的 hasUnmatchedChild 持续为 true，导致父 lambda 在各趟中被死锁。
 * 阶段二时旧中层被判定为孤儿成为幽灵，新中层被迫分配 fresh name，幽灵总数连坐膨胀为 2。
 *
 * <p>本断言把当前已知限制牢牢钉住：
 * <ul>
 *   <li>限制仍存在 -> 记为 KNOWN（套件保持绿灯）；</li>
 *   <li>一旦行为改变（例如 settled 机制生效） -> 记为 FAIL，提示切换至期望版断言。</li>
 * </ul>
 * </p>
 */
public class FixtureAKnownTest {

	static int passed = 0, failed = 0, known = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   PASS  " : "   FAIL  ") + msg);
		if (ok) passed++; else failed++;
	}

	static void checkKnownLimitation(boolean stillBroken, String msg) {
		if (stillBroken) {
			known++;
			System.out.println("   KNOWN " + msg);
		} else {
			failed++;
			System.out.println("   FAIL  [已知限制已变化] " + msg);
		}
	}

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

	static boolean noDup(byte[] b) {
		ClassNode cn = parse(b);
		Map<String, List<String>> byName = new HashMap<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			byName.computeIfAbsent(mn.name, k -> new ArrayList<>()).add(mn.desc);
		}
		for (var e : byName.entrySet()) {
			if (new HashSet<>(e.getValue()).size() != e.getValue().size()) return false;
		}
		return true;
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

		// 在产物中按结构定位活中层（引用产物活叶子的活方法）
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

		// 1. 产物自洽断言（永久成立）
		check(noDup(out), label + orderTag + " 产物自洽（无重复 名字+描述符）");

		// 2. 旧叶子兜底断言（永久成立，旧叶子由于描述符变化且无候选，必须为幽灵）
		check(alignedOldLeaf != null && isGhost(alignedOldLeaf),
			label + orderTag + " 旧叶子 " + oldStruct.leafKey() + " 作为幽灵方法兜底老 CallSite");

		// 3. 已知限制：旧中层被连坐幽灵化
		boolean middleGhosted = alignedOldMiddle != null && isGhost(alignedOldMiddle);
		checkKnownLimitation(middleGhosted,
			label + orderTag + " 旧中层被 hasUnmatchedChild 连坐幽灵化（待 settled 解救）");

		// 4. 已知限制：产物活中层被迫拿 fresh name
		boolean liveMiddleFreshName = liveMiddle != null && !(liveMiddle.name + liveMiddle.desc).equals(oldStruct.middleKey());
		checkKnownLimitation(liveMiddleFreshName,
			label + orderTag + " 产物活中层未能保名，分配了 fresh name: " + liveMidKey);

		// 5. 已知限制：幽灵总数连坐为 2
		checkKnownLimitation(ghostCount == 2,
			label + orderTag + " 幽灵总数连坐膨胀为 2（期望为 1）");

		System.out.println();
	}

	public static void main(String[] args) throws Exception {
		System.out.println("=== 夹具 A 已知限制钉住测试（三 JDK 矩阵 + 正反序）===");

		// 1. JDK 8 矩阵
		testOne("JDK8", "fx/fa8v1/FixtureA.class", "fx/fa8v2/FixtureA.class", false);
		testOne("JDK8", "fx/fa8v1/FixtureA.class", "fx/fa8v2/FixtureA.class", true);

		// 2. JDK 17 矩阵
		testOne("JDK17", "fx/fa17v1/FixtureA.class", "fx/fa17v2/FixtureA.class", false);
		testOne("JDK17", "fx/fa17v1/FixtureA.class", "fx/fa17v2/FixtureA.class", true);

		// 3. JDK 21 矩阵
		testOne("JDK21", "fx/fa21v1/FixtureA.class", "fx/fa21v2/FixtureA.class", false);
		testOne("JDK21", "fx/fa21v1/FixtureA.class", "fx/fa21v2/FixtureA.class", true);

		System.out.println("----------------------------------------");
		System.out.println("通过 " + passed + " 条；失败 " + failed + " 条；已知限制 " + known + " 条");
		System.out.println(failed == 0 ? "FIXTURE-A KNOWN LIMITATIONS OK" : (failed + " FAILED"));
		if (failed != 0) System.exit(1);
	}
}
