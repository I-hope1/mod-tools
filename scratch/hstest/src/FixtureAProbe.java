import nipx.LambdaAligner;

import java.nio.file.*;

/**
 * Fixture A verification entry: does the leaf losing its candidate deadlock the whole chain?
 *
 * Loads the two real javac outputs (FixtureA1 = leaf use(a), FixtureA2 = leaf use(a,b)) and runs
 * align once with DEBUG on, so the post-convergence summary can be read directly.
 *
 * Expected evidence (per the agreed diagnosis):
 *   BLOCKED parent=<middle> by child=<leaf>(childMatched=false, ...)
 *   BLOCKED parent=<outer>  by child=<middle>...
 *   NO-CANDIDATE <leaf> ... 旧侧候选数=0
 *
 * If the leaf instead shows a non-zero candidate count, or the ancestors are not reported as
 * BLOCKED, the deadlock hypothesis is wrong and the cause is elsewhere (shape/upDepth).
 */
public class FixtureAProbe {

	public static void main(String[] args) throws Exception {
		boolean debug = args.length > 0 && "debug".equals(args[0]);
		String d1 = args.length > 1 ? args[1] : "compA/out1";
		String d2 = args.length > 2 ? args[2] : "compA/out2";

		// 打开 DEBUG：dbg 读取的是 volatile 字段，直接赋值即可生效。
		LambdaAligner.DEBUG = debug;

		byte[] oldB = Files.readAllBytes(Paths.get(d1, "FixtureA.class"));
		byte[] newB = Files.readAllBytes(Paths.get(d2, "FixtureA.class"));

		System.out.println("旧类方法签名:");
		dump(oldB);
		System.out.println("新类方法签名:");
		dump(newB);

		System.out.println("--- align (DEBUG=" + debug + ") ---");
		byte[] out = LambdaAligner.align(oldB, newB);
		System.out.println("--- align 返回 " + (out == null ? "null" : out.length + " 字节") + " ---");
		System.out.println("对齐后方法签名:");
		dump(out);
	}

	static void dump(byte[] b) {
		if (b == null) { System.out.println("  (null)"); return; }
		org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(b).accept(cn, 0);
		for (org.objectweb.asm.tree.MethodNode mn : cn.methods) {
			if (mn.name.contains("lambda")) {
				System.out.println("  " + mn.name + mn.desc);
			}
		}
	}
}
