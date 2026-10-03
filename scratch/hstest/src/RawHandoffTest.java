import nipx.AnnotationTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 假设我们改用 raw 基线对齐：LambdaAligner 产出的 renameMap 的 key 是
 * "raw 方法名 + raw 描述符"。但当这个 map 应用到即将 redefine 的字节码上时，
 * forceStaticLambdas 已经把描述符改掉了。这个实验验证 rename 还能不能落地。
 */
public class RawHandoffTest {

	static ClassNode parse(byte[] b) {
		ClassNode cn = new ClassNode();
		new ClassReader(b).accept(cn, 0);
		return cn;
	}

	/** 打印 run() 里 invokedynamic 的 implMethodName / implMethodSignature。 */
	static String indyImpl(byte[] bytes) {
		ClassNode cn = parse(bytes);
		List<String> out = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			for (AbstractInsnNode n : mn.instructions) {
				if (!(n instanceof InvokeDynamicInsnNode indy)) continue;
				if (indy.bsmArgs == null || indy.bsmArgs.length < 2) continue;
				if (indy.bsmArgs[1] instanceof Handle h) {
					out.add(mn.name + " -> indy impl = " + h.getName() + h.getDesc());
				}
			}
		}
		Collections.sort(out);
		return String.join("\n    ", out);
	}

	static final String TARGET = "lambda$build$99";

	/** 模拟 applyTransform：只按 renameMap 改方法名，key 为 "name+desc"。 */
	static byte[] applyRename(byte[] bytes, String owner, Map<String, String> map) {
		ClassNode remapped = new ClassNode();
		ClassRemapper remapper = new ClassRemapper(remapped, new Remapper(Opcodes.ASM9) {
			@Override
			public String mapMethodName(String o, String name, String desc) {
				if (!o.equals(owner)) return name;
				String n = map.get(name + desc);
				return n != null ? n : name;
			}
		});
		new ClassReader(bytes).accept(remapper, 0);
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		remapped.accept(cw);
		return cw.toByteArray();
	}

	public static void main(String[] args) throws Exception {
		byte[] v1 = Files.readAllBytes(Paths.get(args[0]));
		byte[] v2 = Files.readAllBytes(Paths.get(args[1]));
		String slash = new ClassReader(v1).getClassName();
		ClassLoader cl = RawHandoffTest.class.getClassLoader();
		AnnotationTransformer.HierarchyTree.register(v1);
		AnnotationTransformer.HierarchyTree.register(v2);

		byte[] v1f = AnnotationTransformer.forceStaticLambdas(v1, slash, cl);
		byte[] v2f = AnnotationTransformer.forceStaticLambdas(v2, slash, cl);

		// raw 基线时 align 会产生的映射：把 raw 的 lambda$build$0 改名成 TARGET
		Map<String, String> rawKeyedMap = new HashMap<>();
		rawKeyedMap.put("lambda$build$0()V", TARGET);           // key = raw 名 + raw 描述符

		System.out.println("raw v2 的 indy impl:");
		System.out.println("    " + indyImpl(v2));
		System.out.println("\nforced v2 的 indy impl:   <-- 注意描述符已经被 forceStaticLambdas 改掉");
		System.out.println("    " + indyImpl(v2f));

		byte[] out1 = applyRename(v2f, slash, rawKeyedMap);
		System.out.println("\n[用 raw 描述符做 key] 把 renameMap 应用到 forced v2 之后:");
		System.out.println("    " + indyImpl(out1));
		boolean hit1 = indyImpl(out1).contains(TARGET);
		System.out.println("    >>> rename 落地了？ " + hit1);

		Map<String, String> forcedKeyedMap = new HashMap<>();
		forcedKeyedMap.put("lambda$build$0(Ltest2/CtxCase;)V", TARGET);  // key = forced 名 + forced 描述符
		byte[] out2 = applyRename(v2f, slash, forcedKeyedMap);
		System.out.println("\n[用 forced 描述符做 key] 同样操作:");
		System.out.println("    " + indyImpl(out2));
		boolean hit2 = indyImpl(out2).contains(TARGET);
		System.out.println("    >>> rename 落地了？ " + hit2);
	}
}
