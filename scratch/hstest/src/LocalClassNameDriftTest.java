import nipx.AnonClassAligner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §7.2 风险 2（同名局部类编号漂移）的**现状特征化**测试 —— 回答待红用例的三个问题。
 *
 * <p>局部类（具名局部类）当前走"原名直通"：`isAnonymousClassName` 因名字含非数字而拒绝准入，
 * 对齐器从不看它，重定义按类名一一对应。本类把这份现状钉住，并给出三问的实测答案；止血落地后
 * 问题 1 的断言会被改写成"拒绝宿主组"，本类即成为先红后绿的另一半。</p>
 *
 * <p>实测（javac 8/11/17/21 结果一致，见下）：</p>
 * <ol>
 *   <li><b>Q1 原名直通</b>：v1 `Foo$1Helper=TAG_A, Foo$2Helper=TAG_B`；v2 前插同名局部类后
 *       `Foo$1Helper=TAG_N, Foo$2Helper=TAG_A, Foo$3Helper=TAG_B`。按名重定义会把 TAG_A 的存活
 *       实例换成 TAG_N 的实现 —— 与匿名类位移同类的静默错配。</li>
 *   <li><b>Q2 局部类内的匿名类不被准入</b>：`Foo2$1Helper$1` 存在，但 `isAnonymousClassName`
 *       为 false（名字里的 `Helper` 使纯数字后缀校验失败），故同样直通、不受 §1.2 保护。</li>
 *   <li><b>Q3 lambda 体内的局部类</b>：本夹具按源码序编号（`Foo3$1Helper` 先于 `Foo3$2Helper`，
 *       分别对应两个 lambda）。仅此形态一致，不构成对"编号=源码序"的保证。</li>
 * </ol>
 */
class LocalClassNameDriftTest {

	private static final String PKG = "testLocal";

	// ---- 夹具源码：局部类带 static final String TAG（常量变量，Java 8 起局部类亦允许）----

	private static String q1Source(boolean insert) {
		return "package " + PKG + ";\n" +
			"class Foo {\n" +
			(insert
				? "    void runN() { class Helper { static final String TAG = \"TAG_N\"; } System.out.println(Helper.TAG); }\n"
				: "") +
			"    void runA() { class Helper { static final String TAG = \"TAG_A\"; } System.out.println(Helper.TAG); }\n" +
			"    void runB() { class Helper { static final String TAG = \"TAG_B\"; } System.out.println(Helper.TAG); }\n" +
			"}\n";
	}

	private static String q2Source() {
		return "package " + PKG + ";\n" +
			"class Foo2 {\n" +
			"    void go() {\n" +
			"        class Helper { Runnable r = new Runnable() { public void run() {} }; }\n" +
			"        new Helper().r.run();\n" +
			"    }\n" +
			"}\n";
	}

	private static String q3Source() {
		return "package " + PKG + ";\n" +
			"class Foo3 {\n" +
			"    void go() {\n" +
			"        Runnable first  = () -> { class Helper { static final String TAG = \"TAG_A\"; } System.out.println(Helper.TAG); };\n" +
			"        Runnable second = () -> { class Helper { static final String TAG = \"TAG_B\"; } System.out.println(Helper.TAG); };\n" +
			"        first.run(); second.run();\n" +
			"    }\n" +
			"}\n";
	}

	// ---- 工具 ----

	private static File freshDir() throws Exception {
		File d = Files.createTempDirectory("localdrift").toFile();
		d.mkdirs();
		return d;
	}

	private static void compile(String javac, File out, String name, String src) throws Exception {
		AnonClassReproTest.compileInto(javac, out, new File(out, name + ".java"), src);
	}

	private static List<String> classNames(File out) {
		List<String> names = new ArrayList<>();
		File[] fs = new File(out, PKG).listFiles();
		if (fs != null) for (File f : fs) {
			if (f.getName().endsWith(".class")) names.add(f.getName().substring(0, f.getName().length() - 6));
		}
		Collections.sort(names);
		return names;
	}

	/** 读取局部类里的 {@code static final String} 常量（ConstantValue 属性）。 */
	private static String constantString(File out, String simple, String field) throws Exception {
		byte[] b = Files.readAllBytes(new File(new File(out, PKG), simple + ".class").toPath());
		ClassNode cn = new ClassNode();
		new ClassReader(b).accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		if (cn.fields != null) for (FieldNode fn : cn.fields) if (fn.name.equals(field)) return (String) fn.value;
		return null;
	}

	private static String javac(int major) {
		String p = System.getProperty("hstest.javac" + major);
		assertNotNull(p, "缺少 -Dhstest.javac" + major + "（Gradle toolchain 未注入？）");
		return p;
	}

	// ---- 三问 ----

	/** Q1：同名局部类前插导致按名重定义错配（现状：原名直通，对齐器不参与）。 */
	@ParameterizedTest(name = "jdk{0}")
	@ValueSource(ints = { 8, 11, 17, 21 })
	void q1_localClassOriginalNamePassthroughDrifts(int jdk) throws Exception {
		String jc = javac(jdk);
		File v1 = freshDir();
		File v2 = freshDir();
		compile(jc, v1, "Foo", q1Source(false));
		compile(jc, v2, "Foo", q1Source(true));

		assertEquals(List.of("Foo", "Foo$1Helper", "Foo$2Helper"), classNames(v1),
			"v1 应有 A、B 两个同名局部类（遇名即编号）");
		assertEquals(List.of("Foo", "Foo$1Helper", "Foo$2Helper", "Foo$3Helper"), classNames(v2),
			"v2 前插同名局部类后整体后移");

		// 按名重定义：内存中旧的 Foo$1Helper 会被 v2 的 Foo$1Helper 顶替 —— TAG 由 A 变 N。
		assertEquals("TAG_A", constantString(v1, "Foo$1Helper", "TAG"), "v1 的 $1Helper 是 A");
		assertEquals("TAG_B", constantString(v1, "Foo$2Helper", "TAG"), "v1 的 $2Helper 是 B");
		assertEquals("TAG_N", constantString(v2, "Foo$1Helper", "TAG"), "v2 的 $1Helper 是新插入者 N");
		assertEquals("TAG_A", constantString(v2, "Foo$2Helper", "TAG"), "v2 的 $2Helper 才是 A");

		// 现状：对齐器根本不看局部类（名字含非数字，被准入闸门拒绝）→ 无任何防护。
		assertFalse(AnonClassAligner.isAnonymousClassName("testLocal/Foo", "testLocal/Foo$1Helper"),
			"局部类当前不被准入 —— 原名直通，§1.2 不变量对它不成立（风险 2）");
	}

	/** Q2：局部类内部的匿名类同样不被准入（名字含简单名，纯数字后缀校验失败）。 */
	@ParameterizedTest(name = "jdk{0}")
	@ValueSource(ints = { 8, 11, 17, 21 })
	void q2_anonymousInsideLocalClassNotAdmitted(int jdk) throws Exception {
		String jc = javac(jdk);
		File out = freshDir();
		compile(jc, out, "Foo2", q2Source());

		assertEquals(List.of("Foo2", "Foo2$1Helper", "Foo2$1Helper$1"), classNames(out),
			"局部类内的匿名类命名为 Foo2$1Helper$1");
		assertFalse(AnonClassAligner.isAnonymousClassName("testLocal/Foo2", "testLocal/Foo2$1Helper"),
			"局部类本身不准入");
		assertFalse(AnonClassAligner.isAnonymousClassName("testLocal/Foo2", "testLocal/Foo2$1Helper$1"),
			"局部类内部的匿名类也不准入（名字里的 Helper 使纯数字后缀校验失败）→ 直通");
	}

	/** Q3：lambda 体内的局部类，本夹具下编号与源码序一致（仅此形态，非保证）。 */
	@ParameterizedTest(name = "jdk{0}")
	@ValueSource(ints = { 8, 11, 17, 21 })
	void q3_lambdaBodyLocalClassNumbering(int jdk) throws Exception {
		String jc = javac(jdk);
		File out = freshDir();
		compile(jc, out, "Foo3", q3Source());

		assertEquals(List.of("Foo3", "Foo3$1Helper", "Foo3$2Helper"), classNames(out),
			"两个 lambda 内的同名局部类按遇到顺序编号");
		assertEquals("TAG_A", constantString(out, "Foo3$1Helper", "TAG"), "第一个 lambda 的 Helper 编号为 $1");
		assertEquals("TAG_B", constantString(out, "Foo3$2Helper", "TAG"), "第二个 lambda 的 Helper 编号为 $2");
	}

	/** 合成字节码层面的对照：含简单名的名字一律不满足纯数字后缀（Q1/Q2 的直接依据）。 */
	@Test
	void namePatternRejectsNamedLocalSuffixes() {
		assertFalse(AnonClassAligner.isAnonymousClassName("X", "X$1Helper"), "X$1Helper 非纯数字后缀");
		assertFalse(AnonClassAligner.isAnonymousClassName("X", "X$1Helper$1"), "X$1Helper$1 非纯数字后缀");
		assertFalse(AnonClassAligner.isAnonymousClassName("X", "X$1Local"), "X$1Local 非纯数字后缀");
		assertTrue(AnonClassAligner.isAnonymousClassName("X", "X$1"), "X$1 是匿名类");
		assertTrue(AnonClassAligner.isAnonymousClassName("X", "X$1$1"), "X$1$1 是嵌套匿名类");
	}
}
