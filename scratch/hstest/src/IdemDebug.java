import nipx.AnnotationTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/** 逐步诊断：为什么第二次 forceStaticLambdas 仍会再前置一个 this。 */
public class IdemDebug {

	static String dump(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		List<String> out = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			out.add(((mn.access & Opcodes.ACC_STATIC) != 0 ? "static " : "inst   ")
			        + ((mn.access & Opcodes.ACC_SYNTHETIC) != 0 ? "[synth] " : "        ")
			        + mn.name + mn.desc);
		}
		Collections.sort(out);
		return String.join("\n    ", out);
	}

	public static void main(String[] args) throws Exception {
		byte[] v1 = Files.readAllBytes(Paths.get(args[0]));
		String slash = new ClassReader(v1).getClassName();
		ClassLoader cl = IdemDebug.class.getClassLoader();
		AnnotationTransformer.HierarchyTree.register(v1);

		byte[] a = AnnotationTransformer.forceStaticLambdas(v1, slash, cl);
		System.out.println("pass1:\n    " + dump(a));
		byte[] b = AnnotationTransformer.forceStaticLambdas(a, slash, cl);
		System.out.println("pass2:\n    " + dump(b));
		byte[] c = AnnotationTransformer.forceStaticLambdas(b, slash, cl);
		System.out.println("pass3:\n    " + dump(c));
		System.out.println("pass1 == pass2 ? " + Arrays.equals(a, b));
		System.out.println("pass2 == pass3 ? " + Arrays.equals(b, c));
	}
}
