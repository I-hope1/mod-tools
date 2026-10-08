import nipx.AnonClassHasher;
import nipx.AnonClassAligner;
import nipx.LayoutGate;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 字段描述符定向屏蔽的纯函数守卫（合成字节码，不依赖任何 javac）。
 *
 * <p>守的是 §3.1 缺陷的字段变体：嵌套匿名类的 {@code this$N:LOuter$K;} 描述符会随父类位移而变，
 * 必须像方法描述符一样被定向屏蔽（只改写 {@code L本宿主$<纯数字>;}），否则子类内容哈希必变。
 * 移位数不应影响判等；真实差异（主类型、以及两个不同匿名类的**身份模式**）仍须可区分。</p>
 */
class FieldDescriptorMaskTest {

	private static final String HOST = "X";

	/** 合成一个类：给定 {@code [name, desc]} 列表。 */
	private static byte[] cls(String name, String[][] fields) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		for (String[] f : fields) {
			cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, f[0], f[1], null, null).visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static Long hash(String name, String[][] fields) {
		return AnonClassHasher.hash(name, cls(name, fields), HOST, null, null, null, 0);
	}

	// 1) 位移不改判等：this$1:LX$1; 与 this$1:LX$2; 的哈希必须相同（屏蔽生效）。
	@Test
	void thisNShiftIsMasked() {
		Long a = hash("X$1", new String[][]{{"this$1", "LX$1;"}});
		Long b = hash("X$1", new String[][]{{"this$1", "LX$2;"}});
		assertNotNull(a);
		assertEquals(a, b, "this$N 指向本宿主匿名类的描述符位移必须被屏蔽（哈希相等）");
	}

	// 2) 真实类型差异仍须可区分：val$a:I 与 val$a:J（不含 $，屏蔽是恒等）。
	@Test
	void primitiveTypeChangeStillDistinguished() {
		Long i = hash("X$1", new String[][]{{"val$a", "I"}});
		Long j = hash("X$1", new String[][]{{"val$a", "J"}});
		assertNotNull(i);
		assertNotEquals(i, j, "val$a:I 与 val$a:J 不含匿名类引用，屏蔽必须定向、二者仍可区分");
	}

	// 3) 身份模式：{val$a:LX$1;, val$b:LX$2;} 与 {val$a:LX$1;, val$b:LX$1;} 必须不同。
	//    这靠 relId 编号区分（NOT 折入子类内容 —— 那会违反 INV-1）。
	@Test
	void distinctAnonIdentityPatternDistinguished() {
		Long two = hash("X$1", new String[][]{{"val$a", "LX$1;"}, {"val$b", "LX$2;"}});
		Long one = hash("X$1", new String[][]{{"val$a", "LX$1;"}, {"val$b", "LX$1;"}});
		assertNotNull(two);
		assertNotEquals(two, one, "捕获两个不同匿名类 vs 同一个匿名类，身份模式不同，哈希必须不同");
	}

	// 4) 布局门（Tier 4）必须把 this$N 位移判为兼容，否则内容未变的位移会被误拒成孤儿。
	@Test
	void layoutGateTreatsThisNShiftAsCompatible() {
		var oldF = LayoutGate.of(List.of(LayoutGateAssert.fieldNode("this$1", "LX$1;", 0, true)),
			AnonClassAligner.anonymousDescMasker("X"));
		var newF = LayoutGate.of(List.of(LayoutGateAssert.fieldNode("this$1", "LX$2;", 0, true)),
			AnonClassAligner.anonymousDescMasker("X"));
		assertTrue(LayoutGate.check(oldF, newF).compatible(),
			"this$N 指向本宿主匿名类的位移被屏蔽后，布局门必须判兼容（否则误拒成孤儿）");
	}

	// 对照：屏蔽不得扩大为"任意匿名类都兼容"—— 具名内部类引用（非纯数字后缀）保持可区分。
	@Test
	void namedInnerClassRefNotMasked() {
		var oldF = LayoutGate.of(List.of(LayoutGateAssert.fieldNode("f", "LX$Inner;", 0, true)),
			AnonClassAligner.anonymousDescMasker("X"));
		var newF = LayoutGate.of(List.of(LayoutGateAssert.fieldNode("f", "LX$Other;", 0, true)),
			AnonClassAligner.anonymousDescMasker("X"));
		assertFalse(LayoutGate.check(oldF, newF).compatible(),
			"具名内部类引用（后缀非纯数字）不得被屏蔽，类型变化仍须判不兼容");
	}
}
