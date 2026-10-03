import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 带"名字归属"的对拍：
 *   - 旧类每个 lambda 的（名字 -> 语义签名）
 *   - 新类每个 lambda 的语义签名
 *   - 对齐后：新语义签名落到哪个名字上，以及旧名字是否都还在（幽灵/活体）
 * 语义签名用"它最终调用的叶子方法"递归表示，避免只看名字。
 */
public class NameCheck {

	static ClassNode parse(byte[] b) {
		ClassNode cn = new ClassNode();
		new ClassReader(b).accept(cn, 0);
		return cn;
	}

	/** 递归算语义：本方法体里 indy 指向的子 lambda 的语义；叶子则用直接调用的方法名。 */
	static String sem(ClassNode cn, MethodNode mn, int depth) {
		if (depth > 6) return "...";
		Map<String, MethodNode> byName = new HashMap<>();
		for (MethodNode m : cn.methods) byName.put(m.name, m);

		boolean ghost = false;
		List<String> parts = new ArrayList<>();
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof MethodInsnNode m) {
				if (m.owner.equals("nipx/LambdaAligner") && m.name.equals("onOrphanInvoked")) ghost = true;
				else if (m.owner.equals(cn.name) && !m.name.startsWith("lambda$")) parts.add(m.name);
			} else if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
			           && i.bsmArgs[1] instanceof Handle h && cn.name.equals(h.getOwner())) {
				MethodNode child = byName.get(h.getName());
				if (child != null) parts.add(sem(cn, child, depth + 1));
			}
		}
		if (ghost) return "GHOST";
		Collections.sort(parts);
		return parts.toString();
	}

	static Map<String, String> nameToSem(byte[] bytes) {
		ClassNode cn = parse(bytes);
		Map<String, String> m = new TreeMap<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			m.put(mn.name, sem(cn, mn, 0));
		}
		return m;
	}

	public static void main(String[] args) throws Exception {
		byte[] r1 = Files.readAllBytes(Paths.get(args[0]));
		byte[] r2 = Files.readAllBytes(Paths.get(args[1]));
		String slash = new ClassReader(r1).getClassName();
		ClassLoader cl = NameCheck.class.getClassLoader();
		AnnotationTransformer.HierarchyTree.register(r1);
		AnnotationTransformer.HierarchyTree.register(r2);

		byte[] v1 = AnnotationTransformer.forceStaticLambdas(r1, slash, cl);
		byte[] v2 = AnnotationTransformer.forceStaticLambdas(r2, slash, cl);
		byte[] aligned = LambdaAligner.align(v1, v2);

		Map<String, String> oldM = nameToSem(v1);
		Map<String, String> newM = nameToSem(v2);
		Map<String, String> aliM = nameToSem(aligned);

		System.out.println("OLD  (name -> sem):");
		oldM.forEach((k, v) -> System.out.println("   " + k + " -> " + v));
		System.out.println("NEW  (name -> sem):");
		newM.forEach((k, v) -> System.out.println("   " + k + " -> " + v));
		System.out.println("ALIGNED (name -> sem):");
		aliM.forEach((k, v) -> System.out.println("   " + k + " -> " + v));

		System.out.println();
		System.out.println("== 每个新 lambda 的语义最终落在哪个名字上 ==");
		for (var e : newM.entrySet()) {
			String sem = e.getValue();
			List<String> landed = new ArrayList<>();
			aliM.forEach((k, v) -> { if (v.equals(sem)) landed.add(k); });
			System.out.println("   new " + e.getKey() + " (" + sem + ") -> " + landed);
		}

		System.out.println();
		System.out.println("== 旧名字的归宿 ==");
		for (var e : oldM.entrySet()) {
			String now = aliM.getOrDefault(e.getKey(), "<不存在>");
			String verdict;
			if (now.equals(e.getValue())) verdict = "OK       (语义不变)";
			else if (now.equals("GHOST")) verdict = "GHOST    (变空壳)";
			else verdict = "CHANGED  (语义被换成 " + now + ")";
			System.out.println("   " + e.getKey() + " 旧=" + e.getValue() + " 现在=" + now + "   " + verdict);
		}
	}
}
