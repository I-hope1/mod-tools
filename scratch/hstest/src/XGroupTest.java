import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 跨组争抢（Cross-Group Contention）—— **KNOWN LIMITATION，expected-failure**。
 *
 * 夹具：V1 一个孤立闭包；V2 把 methodOld 拆成 methodA/methodB，各自放一个相同闭包。
 * 两个新 lambda 分属两个组，却与老类里孤立的老 lambda 指纹相同。
 *
 * 实测（JDK 8 与 JDK 21 一致）：
 *   正序：lambda$methodB$1 拿到旧名（lambda$methodOld$0 被 methodB 领养）
 *   反序：lambda$methodA$0 拿到旧名
 * => 组序正/反的最终方法表**不一致**：跨组领养是单组循环内的即时贪婪抢占，
 *    谁先被遍历到谁就赢。
 *
 * 本入口**不修**这个缺陷，只把它钉住：
 *   • 若顺序敏感**仍然存在** -> 记一次 KNOWN（套件保持绿）；
 *   • 若某天它消失了 -> 记为 FAIL，必须有人有意识地来更新（可能是修好了）。
 *
 * 这样套件不会常驻红灯，同时"行为一变就会响"。
 */
public class XGroupTest {

	static int passed = 0, failed = 0, known = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   PASS  " : "   FAIL  ") + msg);
		if (ok) passed++; else failed++;
	}

	static void checkKnownLimitation(boolean stillBroken, String msg) {
		if (stillBroken) { known++; System.out.println("   KNOWN " + msg); }
		else { failed++; System.out.println("   FAIL  [已知限制已变化] " + msg); }
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

	static String table(byte[] bytes) {
		ClassNode cn = parse(bytes);
		List<String> l = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			l.add(mn.name + mn.desc + (isGhost(mn) ? "#G" : "#L"));
		}
		Collections.sort(l);
		return String.join(" | ", l);
	}

	static byte[] force(String p, ClassLoader cl) throws Exception {
		byte[] r = Files.readAllBytes(Paths.get(p));
		String s = new ClassReader(r).getClassName();
		AnnotationTransformer.HierarchyTree.register(r);
		return AnnotationTransformer.forceStaticLambdas(r, s, cl);
	}

	static byte[] align(byte[] o, byte[] n, boolean reverse) {
		try {
			LambdaAligner.TEST_REVERSE_GROUP_ORDER = reverse;
			return LambdaAligner.align(o, n);
		} finally {
			LambdaAligner.TEST_REVERSE_GROUP_ORDER = false;
		}
	}

	public static void main(String[] args) throws Exception {
		ClassLoader cl = XGroupTest.class.getClassLoader();
		byte[] v1 = force(args[0], cl), v2 = force(args[1], cl);

		System.out.println("== 跨组争抢（KNOWN LIMITATION）==");
		for (byte[] b : new byte[][]{v1, v2}) {
			ClassNode cn = parse(b);
			List<String> l = new ArrayList<>();
			for (MethodNode mn : cn.methods) if (mn.name.startsWith("lambda$")) l.add(mn.name);
			System.out.println("   " + cn.name + " -> " + l);
		}

		byte[] fwd = align(v1, v2, false);
		byte[] rev = align(v1, v2, true);
		System.out.println("   正序: " + table(fwd));
		System.out.println("   反序: " + table(rev));

		// 前提：两次都必须产出**自洽**的类（不崩、无重名）
		check(noDup(fwd), "正序结果自洽（无重复 名字+描述符）");
		check(noDup(rev), "反序结果自洽（无重复 名字+描述符）");

		boolean orderSensitive = !table(fwd).equals(table(rev));
		checkKnownLimitation(orderSensitive,
			"跨组争抢导致组序敏感：正序/反序的最终方法表不同（正序=" + table(fwd) + " 反序=" + table(rev) + "）");
		if (!orderSensitive) {
			System.out.println("   >>> 顺序敏感已消失 —— 若这是修好的结果，请更新本入口与 README");
		}

		System.out.println();
		System.out.println("通过 " + passed + " 条；失败 " + failed + " 条；已知限制 " + known + " 条");
		System.out.println(failed == 0 ? "XGROUP ASSERTIONS OK" : (failed + " FAILED"));
		if (failed != 0) System.exit(1);
	}

	static boolean noDup(byte[] b) {
		ClassNode cn = parse(b);
		Map<String, List<String>> byName = new HashMap<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			byName.computeIfAbsent(mn.name, k -> new ArrayList<>()).add(mn.desc);
		}
		for (var e : byName.entrySet()) if (new HashSet<>(e.getValue()).size() != e.getValue().size()) return false;
		return true;
	}
}
