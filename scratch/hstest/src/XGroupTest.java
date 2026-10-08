import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跨组争抢（Cross-Group Contention）—— **KNOWN LIMITATION，expected-failure 固化**（JUnit 化）。
 *
 * <p>夹具：V1 一个孤立闭包；V2 把 methodOld 拆成 methodA/methodB，各自放一个相同闭包。
 * 两个新 lambda 分属两个组，却与老类里孤立的老 lambda 指纹相同。组序正/反的最终方法表不一致 ——
 * 单组循环内的即时贪婪抢占。本测试不修它，只钉住：顺序敏感仍存在则绿；一旦消失即变红，逼人更新。</p>
 */
public class XGroupTest {

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

	static String table(byte[] bytes) {
		ClassNode cn = parse(bytes);
		List<String> l = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			l.add(mn.name + mn.desc + (isGhost(mn) ? "#G" : "#L"));
		}
		Collections.sort(l);
		return String.join(" | ", l);
	}

	static byte[] force(Path p, ClassLoader cl) throws Exception {
		byte[] r = Files.readAllBytes(p);
		String s = new ClassReader(r).getClassName();
		AnnotationTransformer.HierarchyTree.register(r);
		return AnnotationTransformer.forceStaticLambdas(r, s, cl);
	}

	static byte[] align(byte[] o, byte[] n, boolean reverse) {
		try {
			LambdaAligner.TEST_REVERSE_GROUP_ORDER = reverse;
			return LambdaAligner.align(o, n);
		} finally {
			LambdaAligner.TEST_REVERSE_GROUP_ORDER = false;
		}
	}

	@Test
	void crossGroupContentionIsOrderSensitive(@TempDir Path tmp) throws Exception {
		ClassLoader cl = XGroupTest.class.getClassLoader();
		for (int major : new int[] { 8, 21 }) {
			String javac = HstestFixtures.javac(major);
			Path d1 = tmp.resolve("x" + major + "v1");
			Path d2 = tmp.resolve("x" + major + "v2");
			HstestFixtures.compile(javac, d1, HstestFixtures.find("xgroup/v1/test25/XGroup.java"));
			HstestFixtures.compile(javac, d2, HstestFixtures.find("xgroup/v2/test25/XGroup.java"));

			byte[] v1 = force(d1.resolve("test25/XGroup.class"), cl);
			byte[] v2 = force(d2.resolve("test25/XGroup.class"), cl);

			byte[] fwd = align(v1, v2, false);
			byte[] rev = align(v1, v2, true);

			assertTrue(noDup(fwd), "JDK" + major + " 正序结果自洽（无重复 名字+描述符）");
			assertTrue(noDup(rev), "JDK" + major + " 反序结果自洽（无重复 名字+描述符）");

			boolean orderSensitive = !table(fwd).equals(table(rev));
			assertTrue(orderSensitive,
				"JDK" + major + " 跨组争抢导致组序敏感：正序/反序的最终方法表不同（正序="
					+ table(fwd) + " 反序=" + table(rev) + "）");
		}
	}

	static boolean noDup(byte[] b) {
		ClassNode cn = parse(b);
		Map<String, List<String>> byName = new HashMap<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			byName.computeIfAbsent(mn.name, k -> new ArrayList<>()).add(mn.desc);
		}
		for (Map.Entry<String, List<String>> e : byName.entrySet()) {
			if (new HashSet<>(e.getValue()).size() != e.getValue().size()) return false;
		}
		return true;
	}
}
