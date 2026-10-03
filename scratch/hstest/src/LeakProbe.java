import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 判别实验（不改产品代码）：两轮 V1→V2→V3 的 $3/$4 互换，是不是"跨调用状态残留"造成的？
 *
 *   模式 A：连续两次 align，中间不动（现状）
 *   模式 B：连续两次 align，第二次之前 LambdaAligner.CONTEXT.remove()
 *   模式 C：把第一轮对齐后的字节码落盘，在**新进程**里只做一次 V2'→V3（见 Save3OneShot）
 *
 * 判据：旧基线里存活名字的子树形状必须不变。
 */
public class LeakProbe {

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

	static String shape(ClassNode cn, Map<String, MethodNode> byName, String name, int d) {
		if (d > 12) return "?";
		MethodNode mn = byName.get(name);
		if (mn == null || isGhost(mn)) return "()";
		List<String> parts = new ArrayList<>();
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
			    && i.bsmArgs[1] instanceof Handle h && cn.name.equals(h.getOwner())) {
				parts.add(shape(cn, byName, h.getName(), d + 1));
			}
		}
		Collections.sort(parts);
		return "(" + String.join("", parts) + ")";
	}

	static Map<String, String> shapeOfAll(byte[] bytes) {
		ClassNode cn = parse(bytes);
		Map<String, MethodNode> byName = new HashMap<>();
		for (MethodNode mn : cn.methods) byName.put(mn.name, mn);
		Map<String, String> out = new TreeMap<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			out.put(mn.name, shape(cn, byName, mn.name, 0));
		}
		return out;
	}

	static byte[] force(String path, ClassLoader cl) throws Exception {
		byte[] r = Files.readAllBytes(Paths.get(path));
		String slash = new ClassReader(r).getClassName();
		AnnotationTransformer.HierarchyTree.register(r);
		return AnnotationTransformer.forceStaticLambdas(r, slash, cl);
	}

	static List<String> mismatches(Map<String, String> base, Map<String, String> fin) {
		List<String> bad = new ArrayList<>();
		for (var e : base.entrySet()) {
			String now = fin.get(e.getKey());
			if (now == null) continue;
			if (!now.equals(e.getValue())) bad.add(e.getKey() + " " + e.getValue() + "->" + now);
		}
		return bad;
	}

	public static void main(String[] args) throws Exception {
		ClassLoader cl = LeakProbe.class.getClassLoader();
		byte[] v1 = force(args[0], cl);
		byte[] v2 = force(args[1], cl);
		byte[] v3 = force(args[2], cl);

		// 模式 A：连续两次，不干预
		byte[] a2 = LambdaAligner.align(v1, v2);
		byte[] a3 = LambdaAligner.align(a2, v3);
		System.out.println("模式 A（连续两次，不干预）      : " + mismatches(shapeOfAll(a2), shapeOfAll(a3)));

		// 模式 B：第二次之前清掉 ThreadLocal 上下文
		byte[] b2 = LambdaAligner.align(v1, v2);
		LambdaAligner.CONTEXT.remove();
		byte[] b3 = LambdaAligner.align(b2, v3);
		System.out.println("模式 B（第二次前 CONTEXT.remove）: " + mismatches(shapeOfAll(b2), shapeOfAll(b3)));

		// 模式 C 的准备：落盘第一轮结果，供新进程单次调用
		Path out = Paths.get(args[3]);
		Files.createDirectories(out.getParent());
		Files.write(out, a2);
		System.out.println("已落盘第一轮对齐结果 -> " + out);

		// 顺带：把两轮基线的 shape 打出来（第 3 点）
		System.out.println();
		System.out.println("第一轮对齐后（两轮基线）的 shape：");
		shapeOfAll(a2).forEach((k, v) -> System.out.println("   " + k + " -> " + v));
		System.out.println("第二轮结束后的 shape：");
		shapeOfAll(a3).forEach((k, v) -> System.out.println("   " + k + " -> " + v));
	}
}
