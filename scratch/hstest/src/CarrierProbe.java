import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 量两件事：
 *   1) 嵌套场景里，外层 lambda 体内是否含"指向本类合成方法的 invokedynamic"（容器特征）
 *   2) 非嵌套的业务 lambda（如 () -> create()）是否也含这个特征（会不会误判）
 */
public class CarrierProbe {

	static String classify(MethodNode mn, String owner) {
		int indyTotal = 0, indySelfSynth = 0, fieldOps = 0;
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof InvokeDynamicInsnNode i) {
				indyTotal++;
				if (i.bsmArgs != null && i.bsmArgs.length > 1 && i.bsmArgs[1] instanceof Handle h) {
					if (owner.equals(h.getOwner()) && h.getName().contains("lambda$")) indySelfSynth++;
				}
			}
			if (n instanceof FieldInsnNode) fieldOps++;
		}
		String kind = indySelfSynth > 0 ? "容器(lambda 体内含内层 lambda)" : "普通业务 lambda";
		return String.format("    %-18s %-26s indy=%d 其中指向本类合成=%d field=%d  -> %s",
			mn.name, mn.desc, indyTotal, indySelfSynth, fieldOps, kind);
	}

	static void dump(String tag, byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		System.out.println("=== " + tag + " (" + cn.name + ") ===");
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			System.out.println(classify(mn, cn.name));
		}
		System.out.println();
	}

	public static void main(String[] args) throws Exception {
		for (String a : args) dump(Paths.get(a).getFileName().toString(), Files.readAllBytes(Paths.get(a)));
	}
}
