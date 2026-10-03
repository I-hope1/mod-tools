import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 夹具 A 期望版断言（Expected Assertion）：叶子描述符变化时，中层应当保名，不被 hasUnmatchedChild 连坐幽灵化。
 *
 * <p>结构定位：
 * <ul>
 *   <li>无子 lambda 者为叶子（Leaf）</li>
 *   <li>引用叶子者为中层（Middle）</li>
 * </ul>
 * 不依赖 javac 生成的具体方法名（JDK 8 的 lambda$null$0 vs JDK 17/21 的 lambda$build$0）。</p>
 *
 * <p>期望验收标准（一律按 name+desc 比较）：
 * <ol>
 *   <li>旧中层（oldMiddle）在最终类里必须是**活方法**（!isGhost），不能成为幽灵；</li>
 *   <li>新中层（newMiddle）在最终类里**沿用旧中层名与描述符**；</li>
 *   <li>旧叶子（oldLeaf）因描述符变化无候选，在最终类里作为**幽灵**（isGhost）存在；</li>
 *   <li>幽灵方法总数**恰好为 1**（只有旧叶子，中层不被连坐）。</li>
 * </ol>
 * </p>
 *
 * <p>当前行为（未实现 settled）：
 * 中层被未落定的叶子死锁，在阶段二全部幽灵化，新中层被迫拿 fresh name（lambda$build$0 / 2 个幽灵）。
 * 因此本断言在当前代码下**必然全红**。</p>
 *
 * <p>settled 修复后的预期最终方法表（以 JDK 8 为例，JDK 17/21 同理只需替换叶子名字）：
 * <pre>
 *   lambda$build$1(II)V   LIVE   新中层沿用旧中层名
 *   lambda$null$0(II)V    LIVE   新叶子保持 javac 原名（与旧 (I)V 描述符不同，不冲突）
 *   lambda$null$0(I)V     GHOST  旧叶子兜底老 CallSite
 * </pre>
 * 幽灵总数恰好为 1，旧中层名 $1 不再是幽灵。settled 完成后直接以此对照；若多出额外行说明引入了副作用。</p>
 */
public class FixtureATest {

	static int passed = 0, failed = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   PASS  " : "   FAIL  ") + msg);
		if (ok) passed++; else failed++;
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

	static byte[] align(byte[] o, byte[] n, boolean reverse) {
		try {
			LambdaAligner.TEST_REVERSE_GROUP_ORDER = reverse;
			return LambdaAligner.align(o, n);
		} finally {
			LambdaAligner.TEST_REVERSE_GROUP_ORDER = false;
		}
	}

	static void testOne(String label, String v1Path, String v2Path, boolean reverse) throws Exception {
		byte[] v1 = Files.readAllBytes(Paths.get(v1Path));
		byte[] v2 = Files.readAllBytes(Paths.get(v2Path));

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
		System.out.println("   [旧类 V1] 结构定位: 中层=" + oldStruct.middleKey() + ", 叶子=" + oldStruct.leafKey());
		System.out.print("   [旧类 V1] 完整方法表: ");
		List<String> m1 = new ArrayList<>();
		for (MethodNode mn : cn1.methods) if (mn.name.startsWith("lambda$")) m1.add(mn.name + mn.desc + (isGhost(mn) ? "#G" : "#L"));
		System.out.println(String.join(", ", m1));

		System.out.println("   [新类 V2] 结构定位: 中层=" + newStruct.middleKey() + ", 叶子=" + newStruct.leafKey());
		System.out.print("   [新类 V2] 完整方法表: ");
		List<String> m2 = new ArrayList<>();
		for (MethodNode mn : cn2.methods) if (mn.name.startsWith("lambda$")) m2.add(mn.name + mn.desc + (isGhost(mn) ? "#G" : "#L"));
		System.out.println(String.join(", ", m2));

		System.out.println("   [产物 out] 完整方法表:");
		for (MethodNode mn : outCn.methods) {
			if (mn.name.startsWith("lambda$")) {
				System.out.println("     " + mn.name + mn.desc + (isGhost(mn) ? " [GHOST]" : " [LIVE]"));
			}
		}

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
		System.out.println("   [产物 out] 结构定位活中层: " + liveMidKey);

		// 期望断言 1：旧中层方法在产物中必须存在且为活方法（不被幽灵化）
		check(alignedOldMiddle != null && !isGhost(alignedOldMiddle),
			label + orderTag + " 旧中层 " + oldStruct.middleKey() + " 必须作为活方法保留（当前状态: "
				+ (alignedOldMiddle == null ? "缺失" : (isGhost(alignedOldMiddle) ? "被幽灵化" : "活方法")) + "）");

		// 期望断言 2：产物中的活中层必须沿用旧中层名与描述符（未被赋予 fresh name）
		check(liveMiddle != null && (liveMiddle.name + liveMiddle.desc).equals(oldStruct.middleKey()),
			label + orderTag + " 产物活中层必须沿用旧中层名 " + oldStruct.middleKey() + "（实际拿了: " + liveMidKey + "）");

		// 期望断言 3：旧叶子由于描述符变化且无候选，必须被幽灵化以保护老 CallSite
		check(alignedOldLeaf != null && isGhost(alignedOldLeaf),
			label + orderTag + " 旧叶子 " + oldStruct.leafKey() + " 必须作为幽灵方法兜底");

		// 期望断言 4：幽灵数量恰好为 1（仅叶子，中层不连坐）
		check(ghostCount == 1,
			label + orderTag + " 幽灵方法总数应恰为 1（实际=" + ghostCount + "）");
		System.out.println();
	}

	public static void main(String[] args) throws Exception {
		System.out.println("=== 夹具 A 期望版断言验证 ===");

		// 1. JDK 8 产物测试（正序 + 反序）
		testOne("JDK8", "scratch/hstest/fx/fa8v1/FixtureA.class", "scratch/hstest/fx/fa8v2/FixtureA.class", false);
		testOne("JDK8", "scratch/hstest/fx/fa8v1/FixtureA.class", "scratch/hstest/fx/fa8v2/FixtureA.class", true);

		// 2. JDK 17 产物测试（正序 + 反序）
		testOne("JDK17", "scratch/hstest/fx/fa17v1/FixtureA.class", "scratch/hstest/fx/fa17v2/FixtureA.class", false);
		testOne("JDK17", "scratch/hstest/fx/fa17v1/FixtureA.class", "scratch/hstest/fx/fa17v2/FixtureA.class", true);

		// 3. JDK 21 产物测试（正序 + 反序）
		testOne("JDK21", "scratch/hstest/fx/fa21v1/FixtureA.class", "scratch/hstest/fx/fa21v2/FixtureA.class", false);
		testOne("JDK21", "scratch/hstest/fx/fa21v1/FixtureA.class", "scratch/hstest/fx/fa21v2/FixtureA.class", true);

		System.out.println("----------------------------------------");
		System.out.println("汇总: 通过 " + passed + " 条；失败 " + failed + " 条");
		if (failed != 0) {
			System.out.println("EXPECTED-ASSERTION RED (符合当前未修状态的预期)");
		} else {
			System.out.println("EXPECTED-ASSERTION GREEN (已修复)");
		}
	}
}
