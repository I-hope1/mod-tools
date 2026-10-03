import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 打印最终类的方法表：名字、描述符、是否幽灵、方法体语义。
 * 用于判定"幽灵遮蔽"是真存在还是我读错了。
 */
public class MethodTable {

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

	static String body(String desc) { return desc; }

	/** 直接调用的本类方法（一层）。 */
	static String calls(MethodNode mn, String owner) {
		List<String> l = new ArrayList<>();
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof MethodInsnNode m && m.owner.equals(owner) && !m.name.startsWith("lambda$")) {
				l.add(m.name);
			}
		}
		return l.toString();
	}

	static void dump(String tag, byte[] bytes) {
		ClassNode cn = parse(bytes);
		System.out.println("=== " + tag + " ===");
		Map<String, Integer> keyCount = new HashMap<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			String key = mn.name + mn.desc;
			keyCount.merge(key, 1, Integer::sum);
			System.out.printf("   %-18s %-26s %-7s calls=%s%n",
				mn.name, mn.desc, isGhost(mn) ? "GHOST" : "live", calls(mn, cn.name));
		}
		boolean dup = false;
		for (var e : keyCount.entrySet()) {
			if (e.getValue() > 1) { dup = true; System.out.println("   !! 重复 key: " + e.getKey() + " x" + e.getValue()); }
		}
		System.out.println("   >>> 是否存在重复的 名字+描述符: " + dup);
		// 同名不同描述符
		Map<String, Set<String>> byName = new TreeMap<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			byName.computeIfAbsent(mn.name, k -> new TreeSet<>()).add(mn.desc);
		}
		for (var e : byName.entrySet()) {
			if (e.getValue().size() > 1) System.out.println("   ~~ 同名不同描述符: " + e.getKey() + " " + e.getValue());
		}
		System.out.println();
	}

	public static void main(String[] args) throws Exception {
		byte[] r1 = Files.readAllBytes(Paths.get(args[0]));
		byte[] r2 = Files.readAllBytes(Paths.get(args[1]));
		String slash = new ClassReader(r1).getClassName();
		ClassLoader cl = MethodTable.class.getClassLoader();
		AnnotationTransformer.HierarchyTree.register(r1);
		AnnotationTransformer.HierarchyTree.register(r2);
		byte[] v1 = AnnotationTransformer.forceStaticLambdas(r1, slash, cl);
		byte[] v2 = AnnotationTransformer.forceStaticLambdas(r2, slash, cl);
		byte[] aligned = LambdaAligner.align(v1, v2);

		dump("旧类 (v1)", v1);
		dump("新类 (v2，未对齐)", v2);
		dump("最终类 (aligned)", aligned);
	}
}
