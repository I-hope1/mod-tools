import java.nio.file.*;

/**
 * 夹具定位抽象（{@code SemAssert} 迁移用）。
 *
 * <p>把"15 个位置参数"换成按 {@code group}/{@code ver} 取名，让 JUnit 与 {@code suite.sh}
 * 共用同一套场景方法：前者走 {@link DirFx}（Gradle 编译产物目录），后者走 {@link ArgsFx}
 * （旧的位置参数，行为完全不变）。</p>
 */
interface Fx {
	String path(String group, String ver);
}

/**
 * 旧入口兼容：{@code suite.sh} 继续传 15 个位置参数，顺序即 {@link #ORDER}。
 */
final class ArgsFx implements Fx {
	/** 与 suite.sh 传给 SemAssert 的 15 个路径逐个对应。 */
	private static final java.util.List<String> ORDER = java.util.List.of(
		"swap2/v1", "swap2/v2", "leaf/v1", "leaf/v2", "leaf/v3",
		"deep/v1", "deep/v2", "two/v1", "two/v2",
		"deep2/v1", "deep2/v2", "deep2/v3",
		"save3/v1", "save3/v2", "save3/v3");

	private final String[] a;

	ArgsFx(String[] a) {
		this.a = a;
	}

	@Override
	public String path(String group, String ver) {
		int i = ORDER.indexOf(group + "/" + ver);
		if (i < 0) throw new IllegalArgumentException("无此夹具: " + group + "/" + ver);
		if (i >= a.length) throw new IllegalStateException(
			"缺少位置参数 " + i + "（" + group + "/" + ver + "）：需要 15 个夹具路径，实到 " + a.length);
		return a[i];
	}
}

/**
 * Gradle 入口：{@code <root>/jdk<N>/<group>/<ver>/<pkg>/<Case>.class}。
 *
 * <p>夹具目录里除 Case 类外还有同包的 helper（如 {@code test16/Time.class}），所以
 * <b>不能</b>靠"唯一顶层 class"取——必须按已知 Case 类名取。</p>
 */
final class DirFx implements Fx {
	/** group → Case 类在输出目录中的相对路径（含包目录）。 */
	private static final java.util.Map<String, String> CASE = java.util.Map.of(
		"swap2", "test16/Swap2Case.class",
		"leaf",  "test17/LeafCase.class",
		"deep",  "test18/DeepCase.class",
		"two",   "test19/TwoLevel.class",
		"deep2", "test20/Deep2.class",
		"save3", "test21/Save3.class");

	private final Path root;

	DirFx(int jdk) {
		String r = System.getProperty("hstest.fixtures");
		if (r == null) throw new IllegalStateException("缺少 -Dhstest.fixtures");
		this.root = Paths.get(r).resolve("jdk" + jdk);
	}

	@Override
	public String path(String group, String ver) {
		String rel = CASE.get(group);
		if (rel == null) throw new IllegalArgumentException("无此夹具组: " + group);
		Path p = root.resolve(group).resolve(ver).resolve(rel);
		if (!Files.isRegularFile(p)) throw new IllegalStateException("夹具缺失: " + p);
		return p.toString();
	}
}
