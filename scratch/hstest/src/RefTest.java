import nipx.AnnotationTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/** 方法引用 this::create 到底生成了什么。 */
public class RefTest {

	static String allMethods(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		List<String> out = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			out.add("    " + ((mn.access & Opcodes.ACC_STATIC) != 0 ? "static " : "inst   ")
				+ ((mn.access & Opcodes.ACC_SYNTHETIC) != 0 ? "[synth] " : "        ")
				+ mn.name + mn.desc);
		}
		Collections.sort(out);
		return String.join("\n", out);
	}

	static String indyImpls(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		List<String> out = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			for (AbstractInsnNode n : mn.instructions) {
				if (!(n instanceof InvokeDynamicInsnNode i)) continue;
				if (i.bsmArgs == null || i.bsmArgs.length < 2) continue;
				if (!(i.bsmArgs[1] instanceof Handle h)) continue;
				out.add("    " + mn.name + " : indy impl = " + h.getTag() + " "
					+ h.getOwner() + "." + h.getName() + h.getDesc());
			}
		}
		Collections.sort(out);
		return String.join("\n", out);
	}

	public static void main(String[] args) throws Exception {
		byte[] v1 = Files.readAllBytes(Paths.get(args[0]));
		String slash = new ClassReader(v1).getClassName();

		System.out.println("=== raw（编译产物）的方法表 ===");
		System.out.println(allMethods(v1));
		System.out.println("\n=== raw 的 invokedynamic 实现句柄 ===");
		System.out.println(indyImpls(v1));
		System.out.println("\n>>> 有 lambda$ 合成方法吗？ " + allMethods(v1).contains("lambda$"));
	}
}
