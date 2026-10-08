import nipx.AnonClassAligner;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §3.1 字段屏蔽的**作用范围差分**（不做黄金哈希，不与补丁版本耦合）。
 *
 * <p>要证明两件事：</p>
 * <ol>
 *   <li><b>范围</b>：{@code maskDescriptor} 只改写 {@code L本宿主$<纯数字>;} 形态；其它字段描述符
 *       必须恒等。对真实嵌套夹具逐字段扫一遍，而不是只看几个手写样例。</li>
 *   <li><b>隔离</b>：字段屏蔽与"方法签名"是两套独立作用域（{@code AnonClassHasher} 用专门的
 *       {@code fieldMasker}）。追加/删除一个带 {@code this$N} 的字段不得改变方法的屏蔽签名集合 ——
 *       否则字段的 relId 消耗会漏进方法哈希，内容未变的重定义也会指纹漂移。</li>
 * </ol>
 *
 * <p>用的是生产钩子 {@link AnonClassAligner#anonymousDescMasker(String)}（内部 {@code MethodFingerprinter}
 * 的 {@code maskDescriptor}），故不是对复制的逻辑自证。</p>
 */
class FieldDescriptorMaskScopeTest {

	/** 合成一个类：给定字段与方法（方法体只有 RETURN，纯粹为读结构）。 */
	private static byte[] cls(String name, String[][] fields, String[][] methods) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		for (String[] f : fields) {
			cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, f[0], f[1], null, null).visitEnd();
		}
		for (String[] m : methods) {
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, m[0], m[1], null, null);
			mv.visitCode();
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static ClassNode node(byte[] b) {
		ClassNode cn = new ClassNode();
		new ClassReader(b).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return cn;
	}

	/** 字段条目集合，口径同 {@code AnonClassHasher}（每类一个 fresh masker，遮蔽 name + masked desc）。 */
	private static List<String> maskedFieldEntries(byte[] b, String host) {
		Function<String, String> mask = AnonClassAligner.anonymousDescMasker(host);
		List<String> out = new ArrayList<>();
		ClassNode cn = node(b);
		if (cn.fields != null) for (FieldNode fn : cn.fields) out.add(fn.name + ":" + mask.apply(fn.desc));
		Collections.sort(out);
		return out;
	}

	/** 非合成、非 lambda 方法的屏蔽签名集合（{@code name + ":" + maskedDesc}，口径同 AnonClassHasher）。 */
	private static List<String> maskedMethodSignatures(byte[] b, String host) {
		Function<String, String> mask = AnonClassAligner.anonymousDescMasker(host);
		List<String> out = new ArrayList<>();
		ClassNode cn = node(b);
		if (cn.methods != null) for (MethodNode mn : cn.methods) {
			if ((mn.access & Opcodes.ACC_SYNTHETIC) == 0 && !mn.name.startsWith("lambda$")) {
				out.add(mn.name + ":" + mask.apply(mn.desc));
			}
		}
		Collections.sort(out);
		return out;
	}

	private static boolean looksLikeAnonRef(String desc, String host) {
		if (desc == null) return false;
		String pre = "L" + host + "$";
		int i = desc.indexOf(pre);
		if (i < 0) return false;
		int end = desc.indexOf(';', i);
		if (end < 0) return false;
		String suffix = desc.substring(i + 1 + host.length() + 1, end);
		return nipx.MethodFingerprinter.isUnstableNestedSuffix(suffix);
	}

	// 1) 范围矩阵：只有 L本宿主$<纯数字>; 被改写，其余一律恒等；位移后的同一引用屏蔽结果相同。
	@Test
	void maskScopeMatrix() {
		String host = "X";
		Function<String, String> mask = AnonClassAligner.anonymousDescMasker(host);

		// 命中：本宿主匿名类引用（含数组/多引用描述符），必须改写。
		assertNotEquals("LX$1;", mask.apply("LX$1;"), "LX$1; 应被屏蔽");
		// 位移（$1 vs $2）必须映射到同一占位——比较时各用 fresh 实例，否则同一实例按首次出现顺序编号。
		assertEquals(AnonClassAligner.anonymousDescMasker(host).apply("LX$1;"),
			AnonClassAligner.anonymousDescMasker(host).apply("LX$2;"), "位移（$1 vs $2）屏蔽后必须相同");
		assertNotEquals("[[LX$1;", mask.apply("[[LX$1;"), "匿名类数组描述符也应屏蔽");
		assertEquals(AnonClassAligner.anonymousDescMasker(host).apply("[[LX$1;"),
			AnonClassAligner.anonymousDescMasker(host).apply("[[LX$2;"), "匿名类数组位移屏蔽后必须相同");
		assertTrue(mask.apply("(LX$1;J)V").contains("#ANON_"), "构造器参数里的匿名类引用应屏蔽");
		// 同一描述符里两个匿名类引用：身份模式必须保留（relId 不同）。
		String two = mask.apply("(LX$1;LX$2;)V");
		String same = mask.apply("(LX$1;LX$1;)V");
		assertNotEquals(two, same, "两个不同匿名类引用 vs 同一个，屏蔽后仍须可区分（身份模式）");

		// 恒等：不含匿名引用的描述符必须原样返回。
		for (String d : new String[]{"I", "J", "Z", "Ljava/lang/String;", "()V", "(I)V",
				"(Ljava/lang/String;)V", "LX$Inner;", "Lfoo/Bar$Baz;", "LX$1Local;"}) {
			assertEquals(d, mask.apply(d), "非匿名引用描述符必须恒等：" + d);
		}
	}

	// 2) 真实嵌套夹具逐字段扫：use-outer 的 this$N（指向本宿主匿名类）被屏蔽，
	//    指向宿主自身的 this$0 与其它字段恒等。
	@Test
	void realNestedFixtureFieldMaskScope() throws Exception {
		String javac = System.getProperty("hstest.javac21");
		assertNotNull(javac, "hstest.javac21 未注入（应由 Gradle toolchain 提供）");
		File dir = Files.createTempDirectory("fdmask").toFile();
		File src = new File(dir, "UseOuter.java");
		Files.writeString(src.toPath(), AnonClassReproTest.useOuterSource("testDeep", "UseOuter", 4, false));
		File out = new File(dir, "out");
		out.mkdirs();
		AnonClassReproTest.runCmd(javac, "-nowarn", "-encoding", "UTF-8",
			"-d", out.getAbsolutePath(), src.getAbsolutePath());

		String host = "testDeep/UseOuter";
		Map<String, byte[]> anon = AnonClassReproTest.anonClasses(out, host);
		assertFalse(anon.isEmpty(), "应能收集到 use-outer 的匿名类");
		Function<String, String> mask = AnonClassAligner.anonymousDescMasker(host);

		int masked = 0, identity = 0;
		for (Map.Entry<String, byte[]> e : anon.entrySet()) {
			ClassNode cn = node(e.getValue());
			if (cn.fields == null) continue;
			for (FieldNode fn : cn.fields) {
				String got = mask.apply(fn.desc);
				if (looksLikeAnonRef(fn.desc, host)) {
					assertNotEquals(fn.desc, got, e.getKey() + "." + fn.name + " 是匿名类引用，应被屏蔽");
					masked++;
				} else {
					assertEquals(fn.desc, got, e.getKey() + "." + fn.name + " 非匿名引用，必须恒等");
					identity++;
				}
			}
		}
		assertTrue(masked >= 3, "4 层 use-outer 至少三层 this$N 指向本宿主匿名类、应被屏蔽，实测 " + masked);
		assertTrue(identity >= 1, "至少 this$0（指向宿主自身）应恒等，实测 " + identity);
	}

	// 3) 隔离差分：给同一方法集追加/删除一个带 this$N 的字段，方法的屏蔽签名集合不变；
	//    字段条目集合则确实变化（证明字段确实参与了内容，不是被忽略）。
	@Test
	void appendingThisNFieldDoesNotChangeMethodSignatures() {
		String host = "X";
		String[][] methods = {{"run", "()V"}, {"<init>", "(LX$1;)V"}};

		byte[] base = cls("X$1", new String[][]{}, methods);
		byte[] withField = cls("X$1", new String[][]{{"this$1", "LX$1;"}}, methods);

		assertEquals(maskedMethodSignatures(base, host), maskedMethodSignatures(withField, host),
			"追加 this$N 字段不得改变方法的屏蔽签名集合（字段/方法作用域必须隔离）");
		assertNotEquals(maskedFieldEntries(base, host), maskedFieldEntries(withField, host),
			"字段条目集合应因新增字段而变化（差分有效，不是两边都空）");

		// 删除方向：从带字段的类出发，去掉该字段后方法签名集合同样不变。
		byte[] removed = cls("X$1", new String[][]{}, methods);
		assertEquals(maskedMethodSignatures(withField, host), maskedMethodSignatures(removed, host),
			"删除 this$N 字段同样不得改变方法的屏蔽签名集合");
	}

	// 4) 隔离的根因：字段屏蔽与方法屏蔽分属不同 masker 实例。共享实例会消耗 relId，
	//    使方法描述符的屏蔽结果漂移 —— 这正是 AnonClassHasher 用专门 fieldMasker 要避免的。
	@Test
	void fieldAndMethodMaskerScopesAreIndependent() {
		String host = "X";
		String methodOnFresh = AnonClassAligner.anonymousDescMasker(host).apply("(LX$1;)V");

		// 生产布局：字段与方法各自 fresh 实例 → 字段的 relId 消耗不影响方法。
		Function<String, String> fieldMasker = AnonClassAligner.anonymousDescMasker(host);
		fieldMasker.apply("LX$7;");
		String methodOnSeparate = AnonClassAligner.anonymousDescMasker(host).apply("(LX$2;)V");
		assertEquals(methodOnFresh, methodOnSeparate,
			"字段在独立实例上屏蔽后，方法描述符屏蔽结果必须不变（作用域隔离）");

		// 反证：共享实例先屏蔽字段再屏蔽方法，方法结果会被 relId 消耗扰动。
		Function<String, String> shared = AnonClassAligner.anonymousDescMasker(host);
		shared.apply("LX$7;");
		String methodOnShared = shared.apply("(LX$2;)V");
		assertNotEquals(methodOnFresh, methodOnShared,
			"共享实例会污染方法屏蔽结果——分离 fieldMasker 的正当性由此钉住");
	}
}
