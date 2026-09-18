package hope.magic.js.test;

import hope.magic.js.runtime.ChainedCallSite;
import hope.magic.js.runtime.JSLinker;
import hope.magic.js.runtime.MagicJIT;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.SwitchPoint;

public class MagicJITSwitchPointInvalidationTest {

	public static class TargetA {
		public int value = 42;

		public String greet() {
			return "hello from A";
		}
	}

	public static class TargetB {
		public int value = 100;

		public String greet() {
			return "hello from B";
		}
	}

	@Test
	public void testSwitchPointLifecycleAndEpoch() {
		Class<?> clazz = TargetA.class;
		int epoch0 = MagicJIT.getEpoch(clazz);
		SwitchPoint sp0 = MagicJIT.getSwitchPoint(clazz);

		Assertions.assertNotNull(sp0, "SwitchPoint should not be null");
		Assertions.assertFalse(sp0.hasBeenInvalidated(), "Initial SwitchPoint must be valid");

		// 执行类失效 (模拟 HotSwap / 类重定义)
		MagicJIT.invalidateClass(clazz);

		Assertions.assertEquals(epoch0 + 1, MagicJIT.getEpoch(clazz), "Epoch must increment on invalidation");
		Assertions.assertTrue(sp0.hasBeenInvalidated(), "Old SwitchPoint must be invalidated");

		SwitchPoint sp1 = MagicJIT.getSwitchPoint(clazz);
		Assertions.assertNotNull(sp1, "New SwitchPoint must not be null");
		Assertions.assertNotSame(sp0, sp1, "New SwitchPoint must be a new instance");
		Assertions.assertFalse(sp1.hasBeenInvalidated(), "New SwitchPoint must be valid");
	}

	@Test
	public void testCallSiteDeoptAndRelinkOnMethodInvoke() throws Throwable {
		TargetA obj = new TargetA();
		MethodHandles.Lookup lookup = MethodHandles.lookup();
		MethodType type = MethodType.methodType(Object.class, Object.class);

		CallSite callSite = JSLinker.bootstrapInvoke(lookup, "greet", type, "greet");
		Assertions.assertTrue(callSite instanceof ChainedCallSite);
		ChainedCallSite site = (ChainedCallSite) callSite;

		// 首次调用：触发 Fallback 链接并安装单态守卫 (内嵌 SwitchPoint)
		Object res1 = site.dynamicInvoker().invokeExact((Object) obj);
		Assertions.assertEquals("hello from A", res1);
		Assertions.assertEquals(1, site.getChainDepth(), "Initial chain depth should be 1");
		Assertions.assertFalse(site.isMegamorphic(), "Should be monomorphic");

		// 触发类失效通知
		MagicJIT.invalidateClass(TargetA.class);

		// 再次调用：SwitchPoint 失效触发 Deopt 回退慢路径，自愈并重新装载新 SwitchPoint
		Object res2 = site.dynamicInvoker().invokeExact((Object) obj);
		Assertions.assertEquals("hello from A", res2);
		Assertions.assertEquals(1, site.getChainDepth(), "Chain depth should reset and remain 1 after relink");
		Assertions.assertFalse(site.isMegamorphic(), "Should not degrade to megamorphic on relink");
	}

	@Test
	public void testCallSitePropertyDeoptAndRelink() throws Throwable {
		TargetA obj = new TargetA();
		MethodHandles.Lookup lookup = MethodHandles.lookup();
		MethodType type = MethodType.methodType(Object.class, Object.class);

		CallSite callSite = JSLinker.bootstrapGetProp(lookup, "value", type, "value");
		ChainedCallSite site = (ChainedCallSite) callSite;

		// 读取属性
		Object val1 = site.dynamicInvoker().invokeExact((Object) obj);
		Assertions.assertEquals(42.0, ((Number) val1).doubleValue());

		// 触发类失效
		MagicJIT.invalidateClass(TargetA.class);

		// 再次读取属性
		Object val2 = site.dynamicInvoker().invokeExact((Object) obj);
		Assertions.assertEquals(42.0, ((Number) val2).doubleValue());
	}

	@Test
	public void testGlobalSwitchPointInvalidateAll() throws Throwable {
		TargetA objA = new TargetA();
		MethodHandles.Lookup lookup = MethodHandles.lookup();
		MethodType type = MethodType.methodType(Object.class, Object.class);

		CallSite callSite = JSLinker.bootstrapInvoke(lookup, "greet", type, "greet");
		ChainedCallSite site = (ChainedCallSite) callSite;

		Object res1 = site.dynamicInvoker().invokeExact((Object) objA);
		Assertions.assertEquals("hello from A", res1);

		SwitchPoint globalSp = MagicJIT.getGlobalSwitchPoint();
		Assertions.assertNotNull(globalSp);
		Assertions.assertFalse(globalSp.hasBeenInvalidated());

		// 全局失效
		MagicJIT.invalidateAll();
		Assertions.assertTrue(globalSp.hasBeenInvalidated(), "Global SwitchPoint must be invalidated");

		// 全局失效后调用，自动恢复并成功调用
		Object res2 = site.dynamicInvoker().invokeExact((Object) objA);
		Assertions.assertEquals("hello from A", res2);
	}

	@Test
	public void testPolymorphismWithSelectiveInvalidation() throws Throwable {
		TargetA objA = new TargetA();
		TargetB objB = new TargetB();

		MethodHandles.Lookup lookup = MethodHandles.lookup();
		MethodType type = MethodType.methodType(Object.class, Object.class);

		CallSite callSite = JSLinker.bootstrapInvoke(lookup, "greet", type, "greet");
		ChainedCallSite site = (ChainedCallSite) callSite;

		// 链接 Class A
		Object resA1 = site.dynamicInvoker().invokeExact((Object) objA);
		Assertions.assertEquals("hello from A", resA1);
		Assertions.assertEquals(1, site.getChainDepth());

		// 链接 Class B (多态)
		Object resB1 = site.dynamicInvoker().invokeExact((Object) objB);
		Assertions.assertEquals("hello from B", resB1);
		Assertions.assertEquals(2, site.getChainDepth());

		// 仅失效 Class A
		SwitchPoint spBBefore = MagicJIT.getSwitchPoint(TargetB.class);
		MagicJIT.invalidateClass(TargetA.class);

		// 验证 Class B 的 SwitchPoint 未被牵连
		Assertions.assertFalse(spBBefore.hasBeenInvalidated(), "TargetB's SwitchPoint should NOT be invalidated");

		// Class A 重新自愈
		Object resA2 = site.dynamicInvoker().invokeExact((Object) objA);
		Assertions.assertEquals("hello from A", resA2);

		// Class B 依然正常执行
		Object resB2 = site.dynamicInvoker().invokeExact((Object) objB);
		Assertions.assertEquals("hello from B", resB2);
	}
}
