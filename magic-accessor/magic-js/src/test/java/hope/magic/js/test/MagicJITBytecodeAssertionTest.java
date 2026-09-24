package hope.magic.js.test;

import hope.magic.annotation.AccessMode;
import hope.magic.js.runtime.MagicJIT;
import hope.magic.runtime.Magic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 针对 MagicJIT Route 2 (MagicHolder 集中式静态分发) 零类生成架构断言测试。
 * <p>
 * 验证核心目标：
 * <ol>
 *   <li><b>零动态类生成断言 (Zero Dynamic Class Generation)：</b>断言无论任何 AccessMode，
 *       均无需为方法或构造器动态发射 ASM 字节码（CLASS_DUMP_HOOK 捕获为 null），彻底杜绝元空间膨胀；</li>
 *   <li><b>零装箱基元直调功能断言：</b>验证基于 MagicHolder 汇聚分发的 {@code invokeInt0~3}、{@code newInstance0~3}
 *       计算结果 100% 准确；</li>
 *   <li><b>多方法并发调用稳定性：</b>验证多方法场景下无需聚合隐藏类，统一直接以 DirectMethodHandle 直调。</li>
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
	public void testMethodInvokerZeroDynamicClassesAndFastExecution() throws Throwable {
		// 1. 创建方法调用器 (无论指定 NESTMATE 还是 UNSAFE_AND_METHODHANDLE)
		MagicJIT.MagicInvoker invoker = MagicJIT.createMethodInvoker(
			SampleTarget.class, "privateMultiply", 2, false, AccessMode.NESTMATE
		);

		Assertions.assertNotNull(invoker, "Method invoker must not be null");

		// 2. 核心架构断言：Route 2 彻底消除动态类生成，零元空间污染
		Assertions.assertNull(
			capturedBytes.get(),
			"Route 2 MagicHolder architecture must NOT generate dynamic classes for method invokers!"
		);

		// 3. 真实调用功能正确性断言 (包含 invokeInt2 与 invoke2)
		SampleTarget target = new SampleTarget(123, "test");
		int intRes = invoker.invokeInt2(target, 6, 7);
		Assertions.assertEquals(42, intRes, "invokeInt2 must compute 6 * 7 = 42");

		Object objRes = invoker.invoke2(target, 10, 20);
		Assertions.assertEquals(200, ((Number) objRes).intValue(), "invoke2 must compute 10 * 20 = 200");
	}

	@Test
	public void testConstructorInvokerZeroDynamicClassesAndCorrectInstantiation() throws Throwable {
		// 1. 创建私有构造器调用器
		MagicJIT.MagicConstructorInvoker ctorInvoker = MagicJIT.createConstructorInvoker(
			SampleTarget.class, 2, AccessMode.NESTMATE
		);

		Assertions.assertNotNull(ctorInvoker, "Constructor invoker must not be null");

		// 2. 核心架构断言：Route 2 彻底消除动态类生成
		Assertions.assertNull(
			capturedBytes.get(),
			"Route 2 MagicHolder architecture must NOT generate dynamic classes for constructor invokers!"
		);

		// 3. 真实构造功能断言
		Object instance = ctorInvoker.newInstance2(888, "createdViaHolder");
		Assertions.assertTrue(instance instanceof SampleTarget);
		SampleTarget st = (SampleTarget) instance;
		Assertions.assertEquals(888, st.getSecret());
		Assertions.assertEquals("createdViaHolder", st.getTag());
	}

	@Test
	public void testLinkToMethodInvokerDirectCall() throws Throwable {
		// 1. 创建 UNSAFE_AND_LINKTO 模式的方法调用器
		MagicJIT.MagicInvoker invoker = MagicJIT.createMethodInvoker(
			SampleTarget.class, "privateMultiply", 2, false, AccessMode.UNSAFE_AND_LINKTO
		);

		Assertions.assertNotNull(invoker, "LinkTo invoker must not be null");

		// 2. 零动态类生成
		Assertions.assertNull(capturedBytes.get(), "No dynamic bytecode dumped");

		// 3. 真实调用功能正确性断言
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

	@Test
	public void testMultiMethodInvocationsWithoutClassGeneration() throws Throwable {
		// 针对 MultiMethodTarget 的 5 个不同方法
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

		// 核心断言：5 个不同方法 0 个动态类生成
		Assertions.assertNull(capturedBytes.get(), "Zero dynamic classes generated for all methods of MultiMethodTarget!");

		// 功能正确性校验
		MultiMethodTarget target = new MultiMethodTarget();
		Assertions.assertEquals(11, inv1.invokeInt1(target, 10));
		Assertions.assertEquals(12, inv2.invokeInt1(target, 10));
		Assertions.assertEquals(30, inv3.invokeInt2(target, 10, 20));
		Assertions.assertEquals("hello:world", inv4.invoke1(target, "world"));
		Assertions.assertEquals(42, inv5.invokeInt0(target));

		// 构造器校验
		MagicJIT.MagicConstructorInvoker c1 = MagicJIT.createConstructorInvoker(MultiMethodTarget.class, 0, AccessMode.NESTMATE);
		MagicJIT.MagicConstructorInvoker c2 = MagicJIT.createConstructorInvoker(MultiMethodTarget.class, 1, AccessMode.NESTMATE);

		Assertions.assertNotNull(c1);
		Assertions.assertNotNull(c2);
		Assertions.assertNotNull(c1.newInstance0());
		Assertions.assertNotNull(c2.newInstance1(99));
	}
}
