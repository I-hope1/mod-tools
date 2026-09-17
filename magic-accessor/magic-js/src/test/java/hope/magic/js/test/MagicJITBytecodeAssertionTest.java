package hope.magic.js.test;

import hope.magic.annotation.AccessMode;
import hope.magic.js.runtime.MagicJIT;
import hope.magic.runtime.Magic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 针对 MagicJIT 动态生成的字节码指令与类加载器架构的严谨断言测试。
 * <p>
 * 验证核心目标：
 * <ol>
 *   <li><b>Nestmate 模式字节码直调断言：</b>反编译验证生成的字节码确实使用了 {@code INVOKEVIRTUAL}，
 *       绝未发射违规的 {@code INVOKESPECIAL}，且绝未静默降级为 {@code linkToSpecial} 回退路径；</li>
 *   <li><b>Nestmate 关系断言：</b>生成的隐式类确实与宿主类同巢（{@code isNestmateOf == true}），直出零包装；</li>
 *   <li><b>LinkTo 模式 @Stable 与 BootLoader 架构断言：</b>依据 JDK 官方 {@code @Stable} 规范要求
 *       （<i>"This annotation only takes effect for fields of classes loaded by the boot loader."</i>），
 *       断言 LinkTo 桥接隐式类定义在 BootLoader（{@code getClassLoader() == null}），并且其 {@code MN} 字段
 *       确实带有 {@code @Stable} 注解，确保 HotSpot C2 完全常量折叠生效。</li>
 * </ol>
 */
public class MagicJITBytecodeAssertionTest {

	public static final class SampleTarget {
		private final int secret;
		private final String tag;

		public SampleTarget() {
			this(100, "default");
		}

		private SampleTarget(int secret, String tag) {
			this.secret = secret;
			this.tag = tag;
		}

		private int privateMultiply(int a, int b) {
			return a * b;
		}

		public int getSecret() {
			return secret;
		}

		public String getTag() {
			return tag;
		}
	}

	private final AtomicReference<byte[]> capturedBytes = new AtomicReference<>();
	private final AtomicReference<String> capturedClassName = new AtomicReference<>();

	@BeforeEach
	public void setUp() {
		Magic.install();
		capturedBytes.set(null);
		capturedClassName.set(null);
		MagicJIT.CLASS_DUMP_HOOK = (name, bytes) -> {
			capturedClassName.set(name);
			capturedBytes.set(bytes);
		};
	}

	@AfterEach
	public void tearDown() {
		MagicJIT.CLASS_DUMP_HOOK = null;
	}

	@Test
	public void testNestmateMethodInvokerUsesInvokevirtualAndNoDegradation() throws Throwable {
		// 1. 创建 Nestmate 模式的方法调用器
		MagicJIT.MagicInvoker invoker = MagicJIT.createMethodInvoker(
			SampleTarget.class, "privateMultiply", 2, false, AccessMode.NESTMATE
		);

		Assertions.assertNotNull(invoker, "Nestmate invoker must not be null");

		// 2. 断言未发生静默降级为 Adapter 包装层
		Class<?> invokerClass = invoker.getClass();
		Assertions.assertFalse(
			invokerClass.getName().contains("MagicBootstrapAdapter"),
			"Nestmate invoker must be raw mono-instance without MagicBootstrapAdapter wrapper, but was: " + invokerClass.getName()
		);

		// 3. 断言属于 SampleTarget 的同巢隐式类 (JEP 181)
		Assertions.assertTrue(
			invokerClass.isNestmateOf(SampleTarget.class),
			"Generated invoker class must be a nestmate of SampleTarget"
		);

		// 4. 断言捕获到的字节码
		byte[] bytes = capturedBytes.get();
		Assertions.assertNotNull(bytes, "CLASS_DUMP_HOOK must have captured generated class bytes");

		String disassembly = MagicJIT.disassemble(bytes);
		System.out.println("=== Disassembled Nestmate Invoker Bytecode ===");
		System.out.println(disassembly);

		// 5. 核心字节码断言：验证确实发射了 INVOKEVIRTUAL
		String expectedVirtualCall = "INVOKEVIRTUAL " +
			SampleTarget.class.getName().replace('.', '/') +
			".privateMultiply (II)I";
		Assertions.assertTrue(
			disassembly.contains(expectedVirtualCall),
			"Disassembled bytecode MUST contain INVOKEVIRTUAL call to privateMultiply! Disassembly:\n" + disassembly
		);

		// 6. 核心反向断言：绝不能出现违规的 INVOKESPECIAL privateMultiply（曾引发 VerifyError 的元凶）
		String illegalSpecialCall = "INVOKESPECIAL " +
			SampleTarget.class.getName().replace('.', '/') +
			".privateMultiply";
		Assertions.assertFalse(
			disassembly.contains(illegalSpecialCall),
			"Disassembled bytecode MUST NOT contain INVOKESPECIAL call to privateMultiply (violates JEP 181)!"
		);

		// 7. 核心反向断言：绝不能降级包含 linkToSpecial
		Assertions.assertFalse(
			disassembly.contains("linkToSpecial"),
			"Nestmate mode MUST NOT fallback or contain linkToSpecial!"
		);

		// 8. 真实调用功能正确性断言 (包含 invokeInt2 与 invoke2)
		SampleTarget target = new SampleTarget(123, "test");
		int intRes = invoker.invokeInt2(target, 6, 7);
		Assertions.assertEquals(42, intRes, "invokeInt2 must compute 6 * 7 = 42");

		Object objRes = invoker.invoke2(target, 10, 20);
		Assertions.assertEquals(200, ((Number) objRes).intValue(), "invoke2 must compute 10 * 20 = 200");
	}

	@Test
	public void testNestmateConstructorInvokerUsesInvokespecialInit() throws Throwable {
		// 1. 创建 Nestmate 模式的私有构造器调用器
		MagicJIT.MagicConstructorInvoker ctorInvoker = MagicJIT.createConstructorInvoker(
			SampleTarget.class, 2, AccessMode.NESTMATE
		);

		Assertions.assertNotNull(ctorInvoker, "Nestmate ctor invoker must not be null");

		byte[] bytes = capturedBytes.get();
		Assertions.assertNotNull(bytes, "Must capture ctor bytes");

		String disassembly = MagicJIT.disassemble(bytes);
		System.out.println("=== Disassembled Nestmate Constructor Bytecode ===");
		System.out.println(disassembly);

		// 私有构造器 <init> 合法使用 INVOKESPECIAL
		String expectedSpecialInit = "INVOKESPECIAL " +
			SampleTarget.class.getName().replace('.', '/') +
			".<init> (ILjava/lang/String;)V";
		Assertions.assertTrue(
			disassembly.contains(expectedSpecialInit),
			"Constructor invocation must legally use INVOKESPECIAL <init>"
		);

		// 真实构造功能断言
		Object instance = ctorInvoker.newInstance2(888, "createdViaNestmate");
		Assertions.assertTrue(instance instanceof SampleTarget);
		SampleTarget st = (SampleTarget) instance;
		Assertions.assertEquals(888, st.getSecret());
		Assertions.assertEquals("createdViaNestmate", st.getTag());
	}

	@Test
	public void testLinkToMethodInvokerHasStableAnnotationInBootLoader() throws Throwable {
		// 1. 创建 UNSAFE_AND_LINKTO 模式的方法调用器
		MagicJIT.MagicInvoker invoker = MagicJIT.createMethodInvoker(
			SampleTarget.class, "privateMultiply", 2, false, AccessMode.UNSAFE_AND_LINKTO
		);

		Assertions.assertNotNull(invoker, "LinkTo invoker must not be null");

		byte[] bytes = capturedBytes.get();
		Assertions.assertNotNull(bytes, "Must capture linkTo bytes");

		String disassembly = MagicJIT.disassemble(bytes);
		System.out.println("=== Disassembled LinkTo Invoker Bytecode ===");
		System.out.println(disassembly);

		// 2. 断言字节码中包含 HotSpot 原生 linkToSpecial 原语
		Assertions.assertTrue(
			disassembly.contains("INVOKESTATIC java/lang/invoke/MethodHandle.linkToSpecial"),
			"LinkTo bytecode must call MethodHandle.linkToSpecial"
		);

		// 3. 断言 MN 字段带有 @Stable 注解
		Assertions.assertTrue(
			disassembly.contains("@Ljdk/internal/vm/annotation/Stable;"),
			"LinkTo MN field MUST be annotated with @Stable for C2 constant folding!"
		);

		// 4. 核心规范断言：验证底层类确实被 BootLoader 加载（满足 @Stable 生效规范）
		// @implNote: This annotation only takes effect for fields of classes loaded by the boot loader.
		Field delegateField = invoker.getClass().getDeclaredField("delegate");
		delegateField.setAccessible(true);
		Object rawDelegate = delegateField.get(invoker);
		ClassLoader bootLoader = rawDelegate.getClass().getClassLoader();
		Assertions.assertNull(
			bootLoader,
			"LinkTo inner class MUST be loaded by the BootLoader (null ClassLoader) so @Stable is recognized by HotSpot C2!"
		);

		// 5. 真实调用功能正确性断言
		SampleTarget target = new SampleTarget();
		int res = invoker.invokeInt2(target, 7, 8);
		Assertions.assertEquals(56, res, "LinkTo invokeInt2 must compute 7 * 8 = 56");
	}

	public static final class MultiMethodTarget {
		private int m1(int x) { return x + 1; }
		private int m2(int x) { return x + 2; }
		private int m3(int x, int y) { return x + y; }
		private String m4(String s) { return "hello:" + s; }
		private int m5() { return 42; }

		public MultiMethodTarget() {}
		private MultiMethodTarget(int x) {}
	}

	public static final class MultiMethodLinkToTarget {
		private int m1(int x) { return x * 2; }
		private int m2(int x) { return x * 3; }
		private int m3(int x, int y) { return x * y; }

		public MultiMethodLinkToTarget() {}
		private MultiMethodLinkToTarget(int x) {}
	}

	@Test
	public void testPerHostHiddenClassAggregationReducesClassCount() throws Throwable {
		java.util.concurrent.atomic.AtomicInteger classDumpCount = new java.util.concurrent.atomic.AtomicInteger();
		MagicJIT.CLASS_DUMP_HOOK = (name, bytes) -> classDumpCount.incrementAndGet();

		// 1. Nestmate 模式：针对 MultiMethodTarget 的 5 个不同方法
		MagicJIT.MagicInvoker inv1 = MagicJIT.createMethodInvoker(MultiMethodTarget.class, "m1", 1, false, AccessMode.NESTMATE);
		MagicJIT.MagicInvoker inv2 = MagicJIT.createMethodInvoker(MultiMethodTarget.class, "m2", 1, false, AccessMode.NESTMATE);
		MagicJIT.MagicInvoker inv3 = MagicJIT.createMethodInvoker(MultiMethodTarget.class, "m3", 2, false, AccessMode.NESTMATE);
		MagicJIT.MagicInvoker inv4 = MagicJIT.createMethodInvoker(MultiMethodTarget.class, "m4", 1, false, AccessMode.NESTMATE);
		MagicJIT.MagicInvoker inv5 = MagicJIT.createMethodInvoker(MultiMethodTarget.class, "m5", 0, false, AccessMode.NESTMATE);

		Assertions.assertNotNull(inv1);
		Assertions.assertNotNull(inv2);
		Assertions.assertNotNull(inv3);
		Assertions.assertNotNull(inv4);
		Assertions.assertNotNull(inv5);

		// 核心断言：5 个不同方法仅生成 1 个同巢隐式类 (降幅 80%)
		Assertions.assertEquals(1, classDumpCount.get(), "Only 1 single Hidden Class should be generated for all methods of MultiMethodTarget!");
		Assertions.assertSame(inv1.getClass(), inv2.getClass(), "All invokers must share the same aggregated hidden class");
		Assertions.assertSame(inv1.getClass(), inv3.getClass(), "All invokers must share the same aggregated hidden class");
		Assertions.assertSame(inv1.getClass(), inv4.getClass(), "All invokers must share the same aggregated hidden class");
		Assertions.assertSame(inv1.getClass(), inv5.getClass(), "All invokers must share the same aggregated hidden class");

		// 功能正确性校验
		MultiMethodTarget target = new MultiMethodTarget();
		Assertions.assertEquals(11, inv1.invokeInt1(target, 10));
		Assertions.assertEquals(12, inv2.invokeInt1(target, 10));
		Assertions.assertEquals(30, inv3.invokeInt2(target, 10, 20));
		Assertions.assertEquals("hello:world", inv4.invoke1(target, "world"));
		Assertions.assertEquals(42, inv5.invokeInt0(target));

		// 2. Nestmate 构造器聚合：2 个不同构造器仅生成 1 个同巢构造器类
		classDumpCount.set(0);
		MagicJIT.MagicConstructorInvoker c1 = MagicJIT.createConstructorInvoker(MultiMethodTarget.class, 0, AccessMode.NESTMATE);
		MagicJIT.MagicConstructorInvoker c2 = MagicJIT.createConstructorInvoker(MultiMethodTarget.class, 1, AccessMode.NESTMATE);

		Assertions.assertNotNull(c1);
		Assertions.assertNotNull(c2);
		Assertions.assertEquals(1, classDumpCount.get(), "Only 1 single Hidden Class should be generated for all constructors of MultiMethodTarget!");
		Assertions.assertSame(c1.getClass(), c2.getClass(), "All constructor invokers must share the same aggregated hidden class");
		Assertions.assertNotNull(c1.newInstance0());
		Assertions.assertNotNull(c2.newInstance1(99));

		// 3. LinkTo 模式：验证 BootLoader 下也是 Per-Host 聚合
		classDumpCount.set(0);
		MagicJIT.MagicInvoker linv1 = MagicJIT.createMethodInvoker(MultiMethodLinkToTarget.class, "m1", 1, false, AccessMode.UNSAFE_AND_LINKTO);
		MagicJIT.MagicInvoker linv2 = MagicJIT.createMethodInvoker(MultiMethodLinkToTarget.class, "m2", 1, false, AccessMode.UNSAFE_AND_LINKTO);
		MagicJIT.MagicInvoker linv3 = MagicJIT.createMethodInvoker(MultiMethodLinkToTarget.class, "m3", 2, false, AccessMode.UNSAFE_AND_LINKTO);

		Assertions.assertNotNull(linv1);
		Assertions.assertNotNull(linv2);
		Assertions.assertNotNull(linv3);
		Assertions.assertEquals(1, classDumpCount.get(), "Only 1 single BootLoader Hidden Class should be generated for all LinkTo methods!");

		Field delegateField = linv1.getClass().getDeclaredField("delegate");
		delegateField.setAccessible(true);
		Class<?> delegateCls1 = delegateField.get(linv1).getClass();
		Class<?> delegateCls2 = delegateField.get(linv2).getClass();
		Class<?> delegateCls3 = delegateField.get(linv3).getClass();

		Assertions.assertSame(delegateCls1, delegateCls2, "All LinkTo invokers must share the same underlying BootLoader hidden class");
		Assertions.assertSame(delegateCls1, delegateCls3, "All LinkTo invokers must share the same underlying BootLoader hidden class");

		MultiMethodLinkToTarget ltarget = new MultiMethodLinkToTarget();
		Assertions.assertEquals(20, linv1.invokeInt1(ltarget, 10));
		Assertions.assertEquals(30, linv2.invokeInt1(ltarget, 10));
		Assertions.assertEquals(200, linv3.invokeInt2(ltarget, 10, 20));
	}
}
