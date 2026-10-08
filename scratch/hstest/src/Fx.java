import java.nio.file.*;

/**
 * 夹具定位抽象（{@code SemAssert} 的 JUnit 化用）。
 *
 * <p>把"15 个位置参数"换成按 {@code group}/{@code ver} 取名。目前只有 {@link DirFx}
 * （Gradle 编译产物目录）一个实现；旧的 {@code ArgsFx} 随 {@code SemAssert.main} 一起删除。</p>
 */
interface Fx {
	String path(String group, String ver);
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
