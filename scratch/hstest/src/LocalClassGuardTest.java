import nipx.AnonClassAligner;
import nipx.HotSwapAgent;
import nipx.LocalClassGuard;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.AbstractInsnNode;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §7.2 风险 2 止血门（{@link LocalClassGuard}）的守卫。
 *
 * <p>分工：{@code LocalClassNameDriftTest} 特征化"现状会漂移"；本类守纯判决函数
 * （分类 / 归并 / dropped 集合 / 三个模式）与接线，并在真实夹具上把"漂移"翻成"拒绝"。</p>
 */
class LocalClassGuardTest {

	private static final Set<String> NO_BATCH = Set.of();

	// ---- 合成 ClassNode ----

	private static ClassNode node(String name, String outerClass, String ownOuter, String ownInner) {
		ClassNode cn = new ClassNode();
		cn.name = name;
		cn.outerClass = outerClass;
		cn.innerClasses = new ArrayList<>();
		cn.innerClasses.add(new InnerClassNode(name, ownOuter, ownInner, 0));
		return cn;
	}

	private static ClassNode local(String hostSlash, String numbered, String simple) {
		return node(hostSlash + "$" + numbered + simple, hostSlash, null, simple);
	}

	private static ClassNode anon(String hostSlash, String numbered) {
		return node(hostSlash + "$" + numbered, hostSlash, null, null);
	}

	// ---- 分类 ----

	@Test
	void classifiesLocalVsAnonymousVsMember() {
		ClassNode loc = local("Foo", "1", "Helper");
		assertTrue(LocalClassGuard.isLocalClass(loc), "outerName==null && innerName!=null && 有 EnclosingMethod = 局部类");
		assertEquals("Foo", LocalClassGuard.hostOf(loc));
		assertEquals("Helper", LocalClassGuard.simpleNameOf(loc));

		ClassNode a = anon("Foo", "1");
		assertFalse(LocalClassGuard.isLocalClass(a), "匿名类 innerName 为 null，不算局部类");
		assertNull(LocalClassGuard.hostOf(a));

		ClassNode member = node("Foo$Bar", null, "Foo", "Bar");
		assertFalse(LocalClassGuard.isLocalClass(member), "具名内部类有 outerName，且无 EnclosingMethod，不算局部类");

		ClassNode noEncl = node("Foo$1Helper", null, null, "Helper");
		assertFalse(LocalClassGuard.isLocalClass(noEncl), "没有 EnclosingMethod 一律不视为局部类（宽严）");
	}

	// ---- 判决：命中 ----

	@Test
	void collisionOnNewSideRejectsWholeHostFamily() {
		List<ClassNode> oldC = List.of(local("Foo", "1", "Helper"), local("Foo", "2", "Helper"));
		List<ClassNode> newC = List.of(local("Foo", "1", "Helper"), local("Foo", "2", "Helper"), local("Foo", "3", "Helper"));
		Set<String> batch = Set.of("Foo", "Foo$1Helper", "Foo$2Helper", "Foo$3Helper", "Other");

		LocalClassGuard.Decision d = LocalClassGuard.decide(oldC, newC, LocalClassGuard.MODE_REJECT, batch);
		assertEquals(LocalClassGuard.Action.REJECT, d.action());
		assertEquals(1, d.collisions().size());
		assertEquals("Foo", d.collisions().get(0).hostSlash);
		assertEquals("Helper", d.collisions().get(0).simpleName);
		assertEquals(Set.of("Foo"), d.hosts());
		assertEquals(Set.of("Foo", "Foo$1Helper", "Foo$2Helper", "Foo$3Helper"), d.dropped(),
			"整族移出 = 宿主 + 本批全部 host$...；Other 不得被带走");
	}

	@Test
	void collisionOnOldSideRejectsDeletionShift() {
		// 删除一个同名局部类：新侧只剩 1 个，旧侧 2 个 —— 只查批次会漏掉这个位移。
		List<ClassNode> oldC = List.of(local("Foo", "1", "Helper"), local("Foo", "2", "Helper"));
		List<ClassNode> newC = List.of(local("Foo", "1", "Helper"));
		Set<String> batch = Set.of("Foo", "Foo$1Helper");

		LocalClassGuard.Decision d = LocalClassGuard.decide(oldC, newC, LocalClassGuard.MODE_REJECT, batch);
		assertEquals(LocalClassGuard.Action.REJECT, d.action(), "旧侧 ≥2 也必须命中（删除导致前移）");
		assertEquals(2, d.collisions().get(0).oldCount);
		assertEquals(1, d.collisions().get(0).newCount);
	}

	@Test
	void overlappingAnonymousClassesOfHostAreDroppedToo() {
		List<ClassNode> oldC = List.of(local("Foo2", "1", "Helper"), local("Foo2", "2", "Helper"));
		List<ClassNode> newC = new ArrayList<>(oldC);
		newC.add(local("Foo2", "3", "Helper"));
		Set<String> batch = Set.of("Foo2", "Foo2$1Helper", "Foo2$2Helper", "Foo2$3Helper", "Foo2$1Helper$1");

		LocalClassGuard.Decision d = LocalClassGuard.decide(oldC, newC, LocalClassGuard.MODE_REJECT, batch);
		assertEquals(LocalClassGuard.Action.REJECT, d.action());
		assertTrue(d.dropped().contains("Foo2$1Helper$1"),
			"局部类内部的匿名类（Foo2$1Helper$1）也必须随族移出，否则仍会漂移");
	}

	// ---- 判决：不命中 / 模式 ----

	@Test
	void uniqueSimpleNamesPassThrough() {
		List<ClassNode> oldC = List.of(local("Foo", "1", "Alpha"), local("Foo", "2", "Beta"));
		List<ClassNode> newC = List.of(local("Foo", "1", "Alpha"), local("Foo", "2", "BetaWithInsert"));
		LocalClassGuard.Decision d = LocalClassGuard.decide(oldC, newC, LocalClassGuard.MODE_REJECT, Set.of("Foo"));
		assertEquals(LocalClassGuard.Action.PASS, d.action(), "简单名各异 -> 编号稳定 -> 放行");
		assertTrue(d.collisions().isEmpty());
	}

	@Test
	void warnModeReportsButDoesNotDrop() {
		List<ClassNode> oldC = List.of(local("Foo", "1", "Helper"), local("Foo", "2", "Helper"));
		List<ClassNode> newC = new ArrayList<>(oldC);
		newC.add(local("Foo", "3", "Helper"));
		LocalClassGuard.Decision d = LocalClassGuard.decide(oldC, newC, LocalClassGuard.MODE_WARN, Set.of("Foo", "Foo$1Helper"));
		assertEquals(LocalClassGuard.Action.PASS, d.action(), "warn 必须放行");
		assertFalse(d.collisions().isEmpty(), "但碰撞必须被报告");
		assertFalse(d.hosts().isEmpty());
		assertTrue(d.dropped().isEmpty(), "warn 不移出任何类");
	}

	@Test
	void offModeSkipsEverything() {
		List<ClassNode> oldC = List.of(local("Foo", "1", "Helper"), local("Foo", "2", "Helper"));
		List<ClassNode> newC = new ArrayList<>(oldC);
		newC.add(local("Foo", "3", "Helper"));
		LocalClassGuard.Decision d = LocalClassGuard.decide(oldC, newC, LocalClassGuard.MODE_OFF, Set.of("Foo"));
		assertEquals(LocalClassGuard.Action.PASS, d.action());
		assertTrue(d.collisions().isEmpty(), "off 直接跳过解析");
	}

	// ---- 真实夹具：把 Q1 的"漂移"翻成"拒绝"（先红后绿的另一半）----

	@Test
	void realQ1FixtureIsRejected() throws Exception {
		String javac = System.getProperty("hstest.javac21");
		assertNotNull(javac, "hstest.javac21 未注入");
		File v1 = Files.createTempDirectory("lcgv1").toFile();
		File v2 = Files.createTempDirectory("lcgv2").toFile();
		AnonClassReproTest.compileInto(javac, v1, new File(v1, "Foo.java"), q1(false));
		AnonClassReproTest.compileInto(javac, v2, new File(v2, "Foo.java"), q1(true));

		List<ClassNode> oldC = parseAll(v1);
		List<ClassNode> newC = parseAll(v2);
		Set<String> batch = new LinkedHashSet<>();
		for (String n : names(v2)) batch.add("testLocal." + n);

		// 前提：夹具里确有两个（旧）/三个（新）同名局部类。
		assertEquals(2, LocalClassGuard.collisions(oldC, newC).get(0).oldCount);
		assertEquals(3, LocalClassGuard.collisions(oldC, newC).get(0).newCount);

		LocalClassGuard.Decision d = LocalClassGuard.decide(oldC, newC, LocalClassGuard.MODE_REJECT, batch);
		assertEquals(LocalClassGuard.Action.REJECT, d.action(), "真实 Q1 夹具在止血门下必须被拒");
		assertEquals(Set.of("testLocal.Foo"), d.hosts());
		assertEquals(Set.of("testLocal.Foo", "testLocal.Foo$1Helper", "testLocal.Foo$2Helper", "testLocal.Foo$3Helper"),
			d.dropped(), "宿主与三个同名局部类整组移出");
	}

	// ---- 接线守卫 ----

	@Test
	void defaultModeIsReject() {
		assertEquals("reject", HotSwapAgent.LOCAL_CLASS_GUARD, "止血门默认 fail-closed");
		assertEquals("reject", LocalClassGuard.MODE_REJECT);
	}

	@Test
	void hotSwapAgentCallsLocalClassGuard() throws Exception {
		byte[] bytes = ownClassBytes(HotSwapAgent.class);
		assertNotNull(bytes, "取不到 HotSwapAgent.class 字节");
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		boolean calls = false;
		for (MethodNode mn : cn.methods) {
			if (mn.instructions == null) continue;
			for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode min
				    && "nipx/LocalClassGuard".equals(min.owner)
				    && "decide".equals(min.name)) {
					calls = true;
				}
			}
		}
		assertTrue(calls, "接线守卫：HotSwapAgent 必须调用 LocalClassGuard.decide，否则止血门不会触发");
	}

	// ---- 工具 ----

	private static String q1(boolean insert) {
		return "package testLocal;\n" +
			"class Foo {\n" +
			(insert ? "    void runN() { class Helper { static final String TAG = \"TAG_N\"; } System.out.println(Helper.TAG); }\n" : "") +
			"    void runA() { class Helper { static final String TAG = \"TAG_A\"; } System.out.println(Helper.TAG); }\n" +
			"    void runB() { class Helper { static final String TAG = \"TAG_B\"; } System.out.println(Helper.TAG); }\n" +
			"}\n";
	}

	private static List<String> names(File out) {
		List<String> names = new ArrayList<>();
		File[] fs = new File(out, "testLocal").listFiles();
		if (fs != null) for (File f : fs) if (f.getName().endsWith(".class")) names.add(f.getName().substring(0, f.getName().length() - 6));
		Collections.sort(names);
		return names;
	}

	private static List<ClassNode> parseAll(File out) throws Exception {
		List<ClassNode> list = new ArrayList<>();
		for (String n : names(out)) {
			ClassNode cn = new ClassNode();
			new ClassReader(Files.readAllBytes(new File(new File(out, "testLocal"), n + ".class").toPath()))
			 .accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			list.add(cn);
		}
		return list;
	}

	private static byte[] ownClassBytes(Class<?> c) throws Exception {
		try (java.io.InputStream in = c.getResourceAsStream(c.getSimpleName() + ".class")) {
			return in == null ? null : in.readAllBytes();
		}
	}
}
