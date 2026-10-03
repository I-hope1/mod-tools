import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 编号平移实验（检验"同名巧合掩盖了顺序依赖"这一假设）。
 *
 * 做法：只把**新类**里 lambda 合成方法的序号整体平移（+OFFSET），
 * 旧类保持原样。这样 doB2 叶子的 javac 名不再与旧叶子名重合，
 * "同名优先"就没法救场 —— 若顺序依赖真实存在，重排方法表应让它暴露。
 *
 * 注意（review 提醒）：
 *   • ClassRemapper 对整个类生效，会连带改 indy 的 Handle —— 这正是想要的；
 *   • 要核对被平移的类里没有 $deserializeLambda$，否则字符串常量也要跟着变；
 *   • 只平移 lambda 家族的名字，不动其它方法。
 */
public class ShiftTest {

	static int failed = 0;
	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   PASS  " : "   FAIL  ") + msg);
		if (!ok) failed++;
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

	static byte[] shuffleMethods(byte[] bytes, long seed) {
		ClassNode cn = parse(bytes);
		if (seed == -1L) Collections.reverse(cn.methods);
		else if (seed > 0) Collections.shuffle(cn.methods, new Random(seed));
		ClassWriter cw = new ClassWriter(0);
		cn.accept(cw);
		return cw.toByteArray();
	}

	/** 把 lambda 家族方法名里的**最后一段数字**整体 +offset（只改新类）。 */
	static byte[] shiftLambdaIndices(byte[] bytes, int offset) {
		ClassNode cn = parse(bytes);
		// 安全核对：存在 $deserializeLambda$ 时字符串常量也要改，这里直接拒绝平移。
		for (MethodNode mn : cn.methods) {
			if (mn.name.equals("$deserializeLambda$")) {
				System.out.println("   [跳过平移] 类里有 $deserializeLambda$，字符串常量需同步修改，本实验未处理");
				return bytes;
			}
		}
		final Map<String, String> map = new HashMap<>();
		for (MethodNode mn : cn.methods) {
			String n = mn.name;
			if (n.startsWith("lambda$") || n.startsWith("$lambda")) {
				int i = n.lastIndexOf('$');
				if (i > 0) {
					String tail = n.substring(i + 1);
					if (tail.chars().allMatch(Character::isDigit)) {
						map.put(n, n.substring(0, i + 1) + (Integer.parseInt(tail) + offset));
					}
				}
			}
		}
		if (map.isEmpty()) return bytes;
		ClassWriter cw = new ClassWriter(0);
		ClassRemapper cr = new ClassRemapper(cw, new Remapper() {
			@Override public String mapMethodName(String owner, String name, String desc) {
				String r = map.get(name);
				return r != null ? r : name;
			}
		});
		new ClassReader(bytes).accept(cr, 0);
		return cw.toByteArray();
	}

	static String names(byte[] b) {
		List<String> l = new ArrayList<>();
		for (MethodNode mn : parse(b).methods) if (mn.name.startsWith("lambda$") || mn.name.startsWith("$lambda")) l.add(mn.name);
		return l.toString();
	}

	static byte[] read(String p) throws Exception { return Files.readAllBytes(Paths.get(p)); }
	static byte[] force(byte[] r, ClassLoader cl) {
		String slash = new ClassReader(r).getClassName();
		AnnotationTransformer.HierarchyTree.register(r);
		return AnnotationTransformer.forceStaticLambdas(r, slash, cl);
	}

	public static void main(String[] args) throws Exception {
		ClassLoader cl = ShiftTest.class.getClassLoader();
		int offset = args.length > 3 ? Integer.parseInt(args[3]) : 10;

		byte[] v1 = force(read(args[0]), cl);
		byte[] v2 = force(read(args[1]), cl);

		// 平移后的新类：doB2 叶子的名字不再与旧叶子名重合
		byte[] v2s = shiftLambdaIndices(v2, offset);
		System.out.println("平移 offset=" + offset);
		System.out.println("  旧类方法顺序: " + names(v1));
		System.out.println("  新类方法顺序(平移后): " + names(v2s));

		byte[] base = LambdaAligner.align(v1, v2);
		Map<String, String> baseSem = semAll(base);
		System.out.println("  基线语义: " + baseSem);

		int pass = 0, fail = 0;
		List<String> badTags = new ArrayList<>();
		for (long seed : new long[]{0L, -1L, 1L, 2L, 3L, 7L, 11L, 42L}) {
			// 平移到两侧？只平移新类。旧类用其原始（已 force）形态。
			byte[] got = LambdaAligner.align(
				shuffleMethods(v1, seed),
				shuffleMethods(v2s, seed));
			Map<String, String> g = semAll(got);
			String tag = seed == 0 ? "原序" : (seed == -1 ? "倒序" : ("seed=" + seed));

			// 判据：旧叶子名（基线里承载 doB 链的那个）在最终类里必须承载 doB2 链
			boolean ok = true;
			StringBuilder why = new StringBuilder();
			for (var e : baseSem.entrySet()) {
				if (!e.getValue().contains("doB")) continue;
				String now = g.get(e.getKey());
				if (now == null) { ok = false; why.append(" 丢失 ").append(e.getKey()); }
				else if (!now.contains("doB2")) { ok = false; why.append(" ").append(e.getKey()).append("=").append(now); }
			}
			if (ok) pass++; else { fail++; badTags.add(tag + "(" + why.toString().trim() + ")"); }
			if (seed == 0) System.out.println("  原序最终语义: " + g);
		}
		System.out.println();
		System.out.println("  通过排列 " + pass + " 个；失败排列 " + fail + " 个 -> " + badTags);
		System.out.println();
		if (fail > 0) {
			System.out.println("SHIFT RESULT: 平移后**出现**顺序敏感 —— 同名巧合确实掩盖了它");
			System.out.println("              失败排列: " + badTags);
		} else {
			System.out.println("SHIFT RESULT: 平移后仍全部通过 —— 同名巧合不是原因，需另找机制");
		}
	}
}
