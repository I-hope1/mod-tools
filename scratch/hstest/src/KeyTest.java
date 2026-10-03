import nipx.AnnotationTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 验证 review 第 2 条：lambda 首个显式参数恰好是本类类型时，
 * forceStaticLambdas 的键口径不一致会产出指向不存在方法的 indy。
 */
public class KeyTest {

	static ClassNode parse(byte[] b) {
		ClassNode cn = new ClassNode();
		new ClassReader(b).accept(cn, 0);
		return cn;
	}

	static String methods(byte[] bytes) {
		ClassNode cn = parse(bytes);
		List<String> out = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			out.add("    " + ((mn.access & Opcodes.ACC_STATIC) != 0 ? "static " : "inst   ")
				+ mn.name + mn.desc);
		}
		Collections.sort(out);
		return String.join("\n", out);
	}

	static String handles(byte[] bytes) {
		ClassNode cn = parse(bytes);
		List<String> out = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			for (AbstractInsnNode n : mn.instructions) {
				if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
				    && i.bsmArgs[1] instanceof Handle h) {
					out.add("    " + mn.name + " indy impl -> " + h.getName() + h.getDesc());
				}
			}
		}
		Collections.sort(out);
		return String.join("\n", out);
	}

	/** 校验每个 indy impl 句柄都能在类里找到对应方法（即不会 BootstrapMethodError）。 */
	static String validate(byte[] bytes) {
		ClassNode cn = parse(bytes);
		Set<String> defined = new HashSet<>();
		for (MethodNode mn : cn.methods) defined.add(mn.name + mn.desc);
		List<String> bad = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			for (AbstractInsnNode n : mn.instructions) {
				if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
				    && i.bsmArgs[1] instanceof Handle h) {
					String key = h.getName() + h.getDesc();
					if (!defined.contains(key)) bad.add("    ✗ indy 指向不存在的 " + key);
				}
			}
		}
		return bad.isEmpty() ? "    ✓ 所有 indy impl 均在类中定义" : String.join("\n", bad);
	}

	static void show(String tag, byte[] b) {
		System.out.println("=== " + tag + " ===");
		System.out.println("  方法定义:");
		System.out.println(methods(b));
		System.out.println("  indy 句柄:");
		System.out.println(handles(b));
		System.out.println("  一致性:");
		System.out.println(validate(b));
		System.out.println();
	}

	public static void main(String[] args) throws Exception {
		byte[] raw = Files.readAllBytes(Paths.get(args[0]));
		String slash = new ClassReader(raw).getClassName();
		ClassLoader cl = KeyTest.class.getClassLoader();
		AnnotationTransformer.HierarchyTree.register(raw);

		byte[] p1 = AnnotationTransformer.forceStaticLambdas(raw, slash, cl);
		byte[] p2 = AnnotationTransformer.forceStaticLambdas(p1, slash, cl);

		show("raw", raw);
		show("pass1", p1);
		show("pass2", p2);
		System.out.println(">>> pass1 == pass2 ? " + Arrays.equals(p1, p2));
	}
}
