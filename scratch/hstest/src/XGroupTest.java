import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 跨组争抢复现：V1 一个孤立闭包；V2 两个不同方法里各一个相同闭包。
 * 两个新 lambda 分属两个组，却与老类的孤立 lambda 指纹相同。
 *
 * 检验那份外部分析的核心主张：**跨组领养是单组循环内的即时贪婪抢占，
 * 谁先被遍历到谁就赢** —— 因此反转组序会翻转配对结果。
 *
 * 判据（确定性，不看名字表）：
 *   A. 组序正/反的最终方法表是否一致（顺序泄漏）
 *   B. 谁拿到了老名字（按语义识别是哪个方法的闭包）
 */
public class XGroupTest {

	static int failed = 0;
	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   PASS  " : "   FAIL  ") + msg);
		if (!ok) failed++;
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

	/** 该方法体里 indy 指向的本类方法名（即它调用的子）。 */
	static String callee(ClassNode cn, MethodNode mn) {
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
			    && i.bsmArgs[1] instanceof Handle h && cn.name.equals(h.getOwner())) return h.getName();
		}
		return "?";
	}

	/** 方法表：名字 + 描述符 + 幽灵位。 */
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

		System.out.println("== 跨组争抢 ==");
		for (byte[] b : new byte[][]{v1, v2}) {
			ClassNode cn = parse(b);
			List<String> l = new ArrayList<>();
			for (MethodNode mn : cn.methods) if (mn.name.startsWith("lambda$")) l.add(mn.name + "->" + callee(cn, mn));
			System.out.println("   " + cn.name + " 的 lambda: " + l);
		}

		byte[] fwd = align(v1, v2, false);
		byte[] rev = align(v1, v2, true);
		System.out.println("   正序结果: " + table(fwd));
		System.out.println("   反序结果: " + table(rev));

		check(table(fwd).equals(table(rev)),
			"组序正/反的最终方法表一致（顺序泄漏检测）");
		System.out.println();
		System.out.println(table(fwd).equals(table(rev))
			? "XGROUP: 未观察到跨组争抢导致的顺序敏感"
			: "XGROUP: 复现成功 —— 跨组争抢确实顺序敏感");
		if (!table(fwd).equals(table(rev))) System.exit(2);   // 用退出码 2 区分"复现"与"断言失败"
	}
}
