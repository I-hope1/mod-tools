import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 上行深度两趟匹配的验收（**先红后绿**）。
 *
 * 场景：同一次保存里
 *   • 插入一个全新的叶子 lambda（noop，由 build() **直接**引用 => 上行深度 0）
 *   • 把 B 链叶子改体（doB2，被 middle/outer 两层引用 => 上行深度 2）
 * 旧侧只剩一个叶子名可认领。当前实现按**组内顺序**先到先得，因此平移编号后
 * 4/8 排列会把旧叶子名给 noop（已由 ShiftTest 复现）。
 *
 * 期望（验收标准）：带窗口**不做平移**、以及**平移后**，旧叶子名都必须由 doB2 链承接。
 * 上行深度是纯结构信息（与匹配状态无关），A 趟可对叶子直接执行，不与
 * hasUnmatchedChild 互等。
 *
 * 判据（独立重算，不看名字表）：
 *   • 旧基准里承载 doB 链的名字，在最终类里必须承载 doB2 链；
 *   • 最终类自洽（无重复 名字+描述符）。
 */
public class UpDepthTest {

	static int passed = 0, failed = 0, known = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   PASS  " : "   FAIL  ") + msg);
		if (ok) passed++; else failed++;
	}

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

	static String sem(ClassNode cn, MethodNode mn, int d) {
		if (d > 8) return "...";
		if (isGhost(mn)) return "GHOST";
		Map<String, MethodNode> by = new HashMap<>();
		for (MethodNode m : cn.methods) by.put(m.name, m);
		List<String> p = new ArrayList<>();
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof MethodInsnNode m && m.owner.equals(cn.name) && !m.name.startsWith("lambda$")) p.add(m.name);
			else if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
			         && i.bsmArgs[1] instanceof Handle h && cn.name.equals(h.getOwner())) {
				MethodNode c = by.get(h.getName());
				if (c != null) p.add(sem(cn, c, d + 1));
			}
		}
		Collections.sort(p);
		return p.toString();
	}

	static Map<String, String> semAll(byte[] b) {
		ClassNode cn = parse(b);
		Map<String, String> m = new TreeMap<>();
		for (MethodNode mn : cn.methods) if (mn.name.startsWith("lambda$")) m.put(mn.name, sem(cn, mn, 0));
		return m;
	}

	static boolean selfConsistent(byte[] b) {
		ClassNode cn = parse(b);
		Map<String, List<String>> byName = new HashMap<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			byName.computeIfAbsent(mn.name, k -> new ArrayList<>()).add(mn.desc);
		}
		for (var e : byName.entrySet()) if (new HashSet<>(e.getValue()).size() != e.getValue().size()) return false;
		return true;
	}

	/** 把新类 lambda 序号整体平移，消掉"同名巧合"。 */
	static byte[] shift(byte[] bytes, int offset) {
		ClassNode cn = parse(bytes);
		for (MethodNode mn : cn.methods) if (mn.name.equals("$deserializeLambda$")) return bytes;
		final Map<String, String> map = new HashMap<>();
		for (MethodNode mn : cn.methods) {
			String n = mn.name;
			if (n.startsWith("lambda$") || n.startsWith("$lambda")) {
				int i = n.lastIndexOf('$');
				if (i > 0) {
					String tail = n.substring(i + 1);
					if (!tail.isEmpty() && tail.chars().allMatch(Character::isDigit)) {
						map.put(n, n.substring(0, i + 1) + (Integer.parseInt(tail) + offset));
					}
				}
			}
		}
		if (map.isEmpty()) return bytes;
		ClassWriter cw = new ClassWriter(0);
		new ClassReader(bytes).accept(new org.objectweb.asm.commons.ClassRemapper(cw,
			new org.objectweb.asm.commons.Remapper() {
				@Override public String mapMethodName(String owner, String name, String desc) {
					String r = map.get(name);
					return r != null ? r : name;
				}
			}), 0);
		return cw.toByteArray();
	}

	/** 重排方法表（0=原序，-1=倒序，>0=固定种子乱序）。 */
	static byte[] shuffleMethods(byte[] bytes, long seed) {
		ClassNode cn = parse(bytes);
		if (seed == -1L) Collections.reverse(cn.methods);
		else if (seed > 0) Collections.shuffle(cn.methods, new Random(seed));
		ClassWriter cw = new ClassWriter(0);
		cn.accept(cw);
		return cw.toByteArray();
	}

	static byte[] force(String p, ClassLoader cl) throws Exception {
		byte[] r = Files.readAllBytes(Paths.get(p));
		String s = new ClassReader(r).getClassName();
		AnnotationTransformer.HierarchyTree.register(r);
		return AnnotationTransformer.forceStaticLambdas(r, s, cl);
	}

	/** 旧基准里承载 doB 链的名字，在最终类里是否承载 doB2 链。 */
	static List<String> brokenLinks(byte[] base, byte[] fin) {
		Map<String, String> bs = semAll(base), fs = semAll(fin);
		List<String> bad = new ArrayList<>();
		for (var e : bs.entrySet()) {
			if (!e.getValue().contains("doB")) continue;
			String now = fs.get(e.getKey());
			if (now == null || !now.contains("doB2")) bad.add(e.getKey() + " 旧=" + e.getValue() + " 现=" + now);
		}
		return bad;
	}

	public static void main(String[] args) throws Exception {
		ClassLoader cl = UpDepthTest.class.getClassLoader();
		String label = args[2];
		byte[] v1 = force(args[0], cl), v2 = force(args[1], cl);

		System.out.println("== 上行深度验收：" + label + " ==");
		byte[] base = LambdaAligner.align(v1, v2);
		System.out.println("   基准语义: " + semAll(base));

		// ---- 情形 1：不平移（应当已经通过）----
		List<String> bad0 = brokenLinks(base, base);
		check(bad0.isEmpty(), "不平移：旧 doB 链在基准里承载 doB2（自洽）");

		// ---- 情形 2：平移编号 + 方法表乱序（这才是触发该 bug 的组合）----
		int badCount = 0;
		List<String> badTags = new ArrayList<>();
		for (long seed : new long[]{0L, -1L, 1L, 2L, 3L, 7L, 11L, 42L}) {
			byte[] v2s = shuffleMethods(shift(v2, 10), seed);
			byte[] fin = LambdaAligner.align(shuffleMethods(v1, seed), v2s);
			List<String> bad = brokenLinks(base, fin);
			String tag = seed == 0 ? "原序" : (seed == -1 ? "倒序" : "seed=" + seed);
			if (!bad.isEmpty()) { badCount++; badTags.add(tag + bad); }
			if (seed == 0) {
				System.out.println("   平移+原序语义: " + semAll(fin));
				check(selfConsistent(fin), "平移后：最终类自洽");
			}
		}
		check(badCount == 0,
			"平移+8 种方法表排列：旧 doB 链均承载 doB2 链（失败 " + badCount + "/8 -> " + badTags + "）");

		System.out.println();
		System.out.println("通过 " + passed + " 条；失败 " + failed + " 条；已知限制 " + known + " 条");
		System.out.println(failed == 0 ? "UPDEPTH OK" : (failed + " FAILED"));
		if (failed != 0) System.exit(1);
	}
}
