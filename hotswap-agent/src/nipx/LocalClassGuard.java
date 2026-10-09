package nipx;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 同名局部类编号漂移防御门（参考 {@code docs/topology/07-layout-gate-and-risks.md} §2）。
 *
 * <p><b>设计文档与状态索引</b>：
 * 设计文档参见 {@code docs/topology/07-layout-gate-and-risks.md} §2；
 * 实现状态参见 {@code docs/status.md} 与 {@code AGENTS.md}。</p>
 *
 * <p>局部类当前原名直通（{@code isAnonymousClassName} 因名字含简单名而拒绝准入），重定义按类名
 * 一一对应。同一宿主内两个同简单名的局部类（ {@code Foo$1Helper}、{@code Foo$2Helper}）在之前
 * 前插/删除/换序一个同名局部类后，物理编号会整体位移 —— 存活实例被无关实现顶替，属**静默错配**。</p>
 *
 * <p>判据：局部类 = 自身 {@code InnerClasses} 条目 {@code outerName == null && innerName != null}
 * 且字节码带 {@code EnclosingMethod}（{@code ClassNode.outerClass != null}）。按
 * {@code (EnclosingMethod.owner, innerName)} 归并；**新旧任一侧**出现同名计数 ≥2 即命中。
 * 带宽严的判定：{@link ClassNode#outerClass} 缺失（例如手工字节码）一律不视为局部类，不触发。</p>
 *
 * <p>本类是纯逻辑，无副作用；接线（取字节、执行移出、回滚事务）在 {@code HotSwapAgent}。</p>
 */
public final class LocalClassGuard {

	public static final String MODE_REJECT = "reject";
	public static final String MODE_WARN   = "warn";
	public static final String MODE_OFF    = "off";

	private LocalClassGuard() { }

	/** 自身 InnerClasses 条目为"局部类"形态：{@code outerName==null && innerName!=null} 且有 EnclosingMethod。 */
	public static boolean isLocalClass(ClassNode cn) {
		if (cn == null || cn.name == null || cn.outerClass == null || cn.innerClasses == null) return false;
		for (InnerClassNode icn : cn.innerClasses) {
			if (cn.name.equals(icn.name)) {
				return icn.outerName == null && icn.innerName != null;
			}
		}
		return false;
	}

	/** 局部类的宿主（{@code EnclosingMethod.owner}，斜杠内部名）；非局部类返回 {@code null}。 */
	public static String hostOf(ClassNode cn) {
		return isLocalClass(cn) ? cn.outerClass : null;
	}

	/** 局部类简单名（{@code innerName}）；非局部类返回 {@code null}。 */
	public static String simpleNameOf(ClassNode cn) {
		if (!isLocalClass(cn)) return null;
		for (InnerClassNode icn : cn.innerClasses) {
			if (cn.name.equals(icn.name)) return icn.innerName;
		}
		return null;
	}

	/** 一个 {@code (宿主, 简单名)} 组的新旧计数；任一侧 ≥2 即碰撞。 */
	public static final class Collision {
		public final String hostSlash;
		public final String simpleName;
		public final int oldCount;
		public final int newCount;

		Collision(String hostSlash, String simpleName, int oldCount, int newCount) {
			this.hostSlash = hostSlash;
			this.simpleName = simpleName;
			this.oldCount = oldCount;
			this.newCount = newCount;
		}

		public boolean collides() { return oldCount >= 2 || newCount >= 2; }

		@Override public String toString() {
			return hostSlash + "$" + simpleName + " (old=" + oldCount + ", new=" + newCount + ")";
		}
	}

	/** 按 {@code (host, simple)} 归并两侧类，返回所有"任一侧 ≥2"的组。 */
	public static List<Collision> collisions(List<ClassNode> oldClasses, List<ClassNode> newClasses) {
		Map<String, int[]> m = new TreeMap<>();
		if (oldClasses != null) for (ClassNode cn : oldClasses) addTo(m, cn, 0);
		if (newClasses != null) for (ClassNode cn : newClasses) addTo(m, cn, 1);
		List<Collision> out = new ArrayList<>();
		for (Map.Entry<String, int[]> e : m.entrySet()) {
			Collision c = decode(e.getKey(), e.getValue());
			if (c.collides()) out.add(c);
		}
		return out;
	}

	public enum Action { PASS, REJECT }

	/**
	 * 判决。
	 *
	 * @param oldClasses 旧（已加载）侧类元数据；可为 null
	 * @param newClasses 新（批次）侧类元数据；可为 null
	 * @param mode       {@code reject}/{@code warn}/{@code off}
	 * @param batchNames 本批类名（点分），用于计算要移出的同前缀家族
	 * @return PASS（无碰撞 / off / warn）或 REJECT（reject 且有碰撞）；{@code hosts} 供日志，
	 *         {@code dropped} 仅 REJECT 时非空 = 宿主 + 其 {@code host$} 前缀的全部批次类。
	 */
	public static Decision decide(List<ClassNode> oldClasses, List<ClassNode> newClasses,
	                              String mode, Set<String> batchNames) {
		if (MODE_OFF.equals(mode)) return new Decision(Action.PASS, List.of(), Set.of(), Set.of());
		List<Collision> cols = collisions(oldClasses, newClasses);
		if (cols.isEmpty()) return new Decision(Action.PASS, List.of(), Set.of(), Set.of());

		Set<String> hosts = new LinkedHashSet<>();
		for (Collision c : cols) hosts.add(c.hostSlash.replace('/', '.'));

		if (MODE_WARN.equals(mode)) return new Decision(Action.PASS, cols, hosts, Set.of());

		Set<String> dropped = new LinkedHashSet<>();
		for (Collision c : cols) {
			String hostDot = c.hostSlash.replace('/', '.');
			dropped.add(hostDot);
			if (batchNames != null) {
				// 保守整族移出：宿主 + 本批里所有 `host$...`（局部类及其嵌套匿名类、匿名子类都在内）。
				// 与布局门刻意保留具名内部类不同 —— 局部类场景下"任何子类编号都不可信"，宁可整组不动。
				for (String n : batchNames) {
					if (n.replace('.', '/').startsWith(c.hostSlash + "$")) dropped.add(n);
				}
			}
		}
		return new Decision(Action.REJECT, cols, hosts, dropped);
	}

	/** 判决结果。 */
	public record Decision(Action action, List<Collision> collisions, Set<String> hosts, Set<String> dropped) { }

	private static void addTo(Map<String, int[]> m, ClassNode cn, int idx) {
		String host = hostOf(cn);
		if (host == null) return;
		String simple = simpleNameOf(cn);
		if (simple == null) return;
		m.computeIfAbsent(host + "\0" + simple, k -> new int[2])[idx]++;
	}

	private static Collision decode(String key, int[] v) {
		int sep = key.indexOf('\0');
		return new Collision(key.substring(0, sep), key.substring(sep + 1), v[0], v[1]);
	}
}
