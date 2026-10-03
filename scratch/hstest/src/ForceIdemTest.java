import nipx.AnnotationTransformer;
import nipx.ClassDiffUtil;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;

public class ForceIdemTest {

	static String methods(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		List<String> out = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			boolean synth = (mn.access & Opcodes.ACC_SYNTHETIC) != 0;
			boolean isStatic = (mn.access & Opcodes.ACC_STATIC) != 0;
			if (mn.name.startsWith("lambda$") || synth) {
				out.add((isStatic ? "static " : "inst   ") + mn.name + mn.desc);
			}
		}
		Collections.sort(out);
		return String.join("\n    ", out);
	}

	static String diffLine(byte[] oldB, byte[] newB) {
		ClassDiffUtil.ClassDiff d = ClassDiffUtil.diff(oldB, newB);
		return "added=" + d.addedMethods + " removed=" + d.removedMethods;
	}

	/** 把字节码写到临时目录，用 URLClassLoader 实际加载并反射调用 run()/r1()，验证无 VerifyError。 */
	static String loadAndRun(String tag, byte[] bytes, String slash) throws Exception {
		Path dir = Files.createTempDirectory("hsrun");
		Path out = dir.resolve(slash + ".class");
		Files.createDirectories(out.getParent());
		Files.write(out, bytes);
		try (URLClassLoader cl = new URLClassLoader(new URL[]{dir.toUri().toURL()},
			ForceIdemTest.class.getClassLoader())) {
			Class<?> c = Class.forName(slash.replace('/', '.'), true, cl);
			Object   o = c.getDeclaredConstructor().newInstance();
			StringBuilder sb = new StringBuilder();
			sb.append("[").append(tag).append("] loaded OK  ");
			Method run = c.getMethod("run");
			run.invoke(o);
			sb.append("| run() OK  ");
			Method r1 = c.getMethod("r1");
			((Runnable) r1.invoke(o)).run();
			sb.append("| r1() OK");
			return sb.toString();
		}
	}

	public static void main(String[] args) throws Exception {
		byte[] v1 = Files.readAllBytes(Paths.get(args[0]));
		byte[] v2 = Files.readAllBytes(Paths.get(args[1]));

		String slash = new ClassReader(v1).getClassName();
		ClassLoader cl = ForceIdemTest.class.getClassLoader();
		AnnotationTransformer.HierarchyTree.register(v1);
		AnnotationTransformer.HierarchyTree.register(v2);

		System.out.println("=========== v1 (raw compiled) ===========");
		System.out.println("    " + methods(v1));

		byte[] v1f = AnnotationTransformer.forceStaticLambdas(v1, slash, cl);
		System.out.println("=========== v1 (forced #1) ===========");
		System.out.println("    " + methods(v1f));

		byte[] v1f2 = AnnotationTransformer.forceStaticLambdas(v1f, slash, cl);
		System.out.println("=========== v1 (forced #2, idempotency check) ===========");
		System.out.println("    " + methods(v1f2));
		System.out.println(">>> forceStaticLambdas IDEMPOTENT = " + Arrays.equals(v1f, v1f2));

		System.out.println("=========== v2 (raw compiled) ===========");
		System.out.println("    " + methods(v2));

		byte[] v2f = AnnotationTransformer.forceStaticLambdas(v2, slash, cl);
		System.out.println("=========== v2 (forced #1) ===========");
		System.out.println("    " + methods(v2f));

		System.out.println();
		System.out.println("=========== Case A: raw v2 aligned against forced v1 (no fix 2) ===========");
		byte[] a = LambdaAligner.align(v1f, v2);
		System.out.println("    " + methods(a));
		System.out.println("    DIFF vs old: " + diffLine(v1f, a));

		System.out.println();
		System.out.println("=========== Case B: forced v2 aligned against forced v1 (with fix 2) ===========");
		byte[] b = LambdaAligner.align(v1f, v2f);
		System.out.println("    " + methods(b));
		System.out.println("    DIFF vs old: " + diffLine(v1f, b));

		System.out.println();
		System.out.println("=========== Case C: forced v1 aligned against forced v1 (no-op check) ===========");
		byte[] c = LambdaAligner.align(v1f, v1f);
		System.out.println("    " + methods(c));
		System.out.println("    DIFF vs old: " + diffLine(v1f, c));

		System.out.println();
		System.out.println("=========== Runtime verification (URLClassLoader + reflection) ===========");
		System.out.println(loadAndRun("v2-forced", v2f, slash));
		System.out.println(loadAndRun("Case B (aligned)", b, slash));

		// ==== 用户实测场景：raw v3 的 lambda 仍是"实例方法"且描述符与 v1 完全同形（都捕获 loc） ====
		byte[] v3 = Files.readAllBytes(Paths.get(args[2]));
		AnnotationTransformer.HierarchyTree.register(v3);
		System.out.println();
		System.out.println("=========== v3 (raw compiled, desc 与 v1 完全同形，只是 instance) ===========");
		System.out.println("    " + methods(v3));

		byte[] d = LambdaAligner.align(v1f, v3);
		System.out.println("===== Case D: align(forcedV1, RAW v3)  ← 修复前 LambdaAligner 的输入 =====");
		System.out.println("    " + methods(d));
		System.out.println("    DIFF vs old: " + diffLine(v1f, d));

		byte[] v3f = AnnotationTransformer.forceStaticLambdas(v3, slash, cl);
		byte[] e = LambdaAligner.align(v1f, v3f);
		System.out.println("===== Case E: align(forcedV1, forcedV3)  ← 修复后的调用时序 =====");
		System.out.println("    " + methods(e));
		System.out.println("    DIFF vs old: " + diffLine(v1f, e));
		System.out.println(loadAndRun("Case E (aligned)", e, slash));
	}
}
