package hope.magic.js.test;

import hope.magic.js.runtime.ChainedCallSite;
import hope.magic.js.runtime.JSContext;
import hope.magic.js.runtime.JSUndefined;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;

/**
 * 验证 JS 调用 Java 架构中已识别的三大类缺陷的复现测试：
 * 1. 分类一：重载解析与调用分发缺陷 (CallSite 类型锁定、invokeMatchedMethod 盲查第一声明)
 * 2. 分类二：CallSite 内联缓存与守卫漏装缺陷 (newFallback 构造器 IC 漏装)
 * 3. 分类三：语言规范与返回值一致性缺陷 (void 返回 null 而非 undefined)
 */
public class JavaInteropBugVerificationTest {

	// --- 测试目标实体类 ---

	public static class OverloadTarget {
		public String execute(int x) {
			return "int:" + x;
		}

		public String execute(String s) {
			return "string:" + s;
		}
	}

	public static class DeclarationOrderTarget {
		// 故意先声明 String 版本，再声明 int 版本
		public String process(String s) {
			return "str:" + s;
		}

		public String process(int i) {
			return "num:" + i;
		}
	}

	public static class VoidTarget {
		public boolean invoked = false;

		public void doNothing() {
			invoked = true;
		}
	}

	public static class SimpleConstructorTarget {
		public final int val;

		public SimpleConstructorTarget() {
			this.val = 42;
		}
	}

	public static class BeanTarget {
		private String hiddenVal = "initial";

		public String getDisplayName() {
			return hiddenVal;
		}

		public void setDisplayName(String name) {
			this.hiddenVal = name;
		}
	}

	/**
	 * 验证 Bug 1.1: CallSite 单态守卫丢弃参数导致同 arity 重载方法被错误类型锁定
	 */
	@Test
	public void testBug1_1_CallSiteOverloadTypeLocking() {
		JSContext cx = new JSContext();
		cx.set("target", new OverloadTarget());
		String script = """
			function callOverload(obj, val) {
				return obj.execute(val);
			}
			// 首次调用：传入整数，CallSite 绑定 execute(int)
			let r1 = callOverload(target, 100);
			// 二次调用：相同 CallSite 传入字符串，按预期应决议为 execute(String)
			let r2 = callOverload(target, "hello");
			[r1, r2];
			""";
		cx.eval(script);
		Object r1 = cx.eval("r1;");
		Object r2 = cx.eval("r2;");

		Assertions.assertEquals("int:100", r1);
		// 当前 Bug 状态下：由于 Guard 丢弃了参数检查，r2 被错误走入 execute(int)，"hello" 被转为 0，结果为 "int:0"！
		// 正确预期应为 "string:hello"
		System.out.println("[Bug 1.1 现象] r1=" + r1 + ", r2=" + r2);
		Assertions.assertEquals("string:hello", r2, "Overloaded method with different arg types must be correctly dispatched instead of locked to the first type!");
	}

	/**
	 * 验证 Bug 1.2: invokeMatchedMethod 依据 (methodName, arity, isStatic) 盲查第一声明导致的错配/类型转换崩溃
	 */
	@Test
	public void testBug1_2_InvokeMatchedMethodBlindFirstDeclaration() throws Throwable {
		DeclarationOrderTarget target = new DeclarationOrderTarget();
		// 直接通过 invokeGeneric 调用 process(123)
		// 内部调用 MethodResolver.findBestMatchingMethod 找到 process(int)
		// 然后进入 invokeMatchedMethod，由于 MagicJIT.getMethodInvoker 盲查第一声明，
		// 会获取到 process(String) 的调用器，并将转好的 Integer 传入，导致抛出 ClassCastException 或返回错误结果！
		Object res = hope.magic.js.runtime.JSLinker.invokeGeneric(target, new Object[]{123}, "process");
		System.out.println("[Bug 1.2 现象] res=" + res);
		Assertions.assertEquals("num:123", res, "Should invoke process(int) and return 'num:123'");
	}

	/**
	 * 验证 Bug 2.1: newFallback 在默认 HYBRID 策略下完全未挂载 CallSite 守卫
	 */
	@Test
	public void testBug2_1_ConstructorCallSiteMissingGuard() throws Throwable {
		JSContext cx = new JSContext();
		cx.set("TargetCls", SimpleConstructorTarget.class);
		String script = """
			function create() {
				return new TargetCls();
			}
			create();
			create();
			create();
			""";
		cx.eval(script);

		Object created = cx.eval("create();");
		Assertions.assertInstanceOf(SimpleConstructorTarget.class, created);

		// 直接测试 bootstrapNew 返回的 CallSite 在 newFallback 执行后的守卫安装情况
		java.lang.invoke.MethodType type = java.lang.invoke.MethodType.methodType(Object.class, Object.class);
		hope.magic.js.runtime.ChainedCallSite site = (hope.magic.js.runtime.ChainedCallSite) hope.magic.js.runtime.JSLinker.bootstrapNew(
			java.lang.invoke.MethodHandles.lookup(), "new", type
		);
		Assertions.assertEquals(0, site.getChainDepth(), "Initial chainDepth must be 0");
		// 首次调用 new SimpleConstructorTarget()
		Object instance = site.getTarget().invokeExact((Object) SimpleConstructorTarget.class);
		Assertions.assertInstanceOf(SimpleConstructorTarget.class, instance);
		// 当前 Bug 状态下：由于 HYBRID 模式直接 return ctorInvoker.newInstance0()，chainDepth 仍然为 0，没有挂载守卫！
		System.out.println("[Bug 2.1 现象] site.getChainDepth()=" + site.getChainDepth());
		Assertions.assertTrue(site.getChainDepth() > 0, "Constructor CallSite must install Guard in HYBRID mode!");
	}

	/**
	 * 验证 Bug 2.2: JavaBean Getter / Setter 均未挂载 CallSite 守卫
	 */
	@Test
	public void testBug2_2_JavaBeanGetterSetterMissingGuard() throws Throwable {
		BeanTarget bean = new BeanTarget();
		java.lang.invoke.MethodType getterType = java.lang.invoke.MethodType.methodType(Object.class, Object.class);
		hope.magic.js.runtime.ChainedCallSite getSite = (hope.magic.js.runtime.ChainedCallSite) hope.magic.js.runtime.JSLinker.bootstrapGetProp(
			java.lang.invoke.MethodHandles.lookup(), "getProp", getterType, "displayName"
		);
		Assertions.assertEquals(0, getSite.getChainDepth());
		Object val = getSite.getTarget().invokeExact((Object) bean);
		Assertions.assertEquals("initial", val);
		// 当前 Bug: getPropFallback 并没有为 JavaBean getter 安装 Guard，chainDepth 仍然为 0！
		System.out.println("[Bug 2.2 现象] getSite.getChainDepth()=" + getSite.getChainDepth());
		Assertions.assertTrue(getSite.getChainDepth() > 0, "JavaBean getter CallSite must install Guard!");

		java.lang.invoke.MethodType setterType = java.lang.invoke.MethodType.methodType(void.class, Object.class, Object.class);
		hope.magic.js.runtime.ChainedCallSite setSite = (hope.magic.js.runtime.ChainedCallSite) hope.magic.js.runtime.JSLinker.bootstrapSetProp(
			java.lang.invoke.MethodHandles.lookup(), "setProp", setterType, "displayName"
		);
		Assertions.assertEquals(0, setSite.getChainDepth());
		setSite.getTarget().invokeExact((Object) bean, (Object) "updated");
		Assertions.assertEquals("updated", bean.getDisplayName());
		// 当前 Bug: setPropFallback 并没有为 JavaBean setter 安装 Guard，chainDepth 仍然为 0！
		System.out.println("[Bug 2.2 现象] setSite.getChainDepth()=" + setSite.getChainDepth());
		Assertions.assertTrue(setSite.getChainDepth() > 0, "JavaBean setter CallSite must install Guard!");
	}

	/**
	 * 验证 Bug 3.1: void 返回值在 SPREADER 模式与 invokeGeneric 路径下返回 null 而非 JSUndefined
	 */
	@Test
	public void testBug3_1_VoidMethodReturnsNullInSpreaderAndGeneric() throws Throwable {
		// 1. 测试 invokeGeneric 分支
		Object genericRes = hope.magic.js.runtime.JSLinker.invokeGeneric(new VoidTarget(), new Object[0], "doNothing");
		System.out.println("[Bug 3.1 现象 invokeGeneric] genericRes=" + genericRes);
		Assertions.assertSame(JSUndefined.INSTANCE, genericRes, "void method under invokeGeneric must return undefined, not null!");

		// 2. 测试 SPREADER 策略分支
		hope.magic.js.runtime.JSLinker.InvocationStrategy oldStrategy = hope.magic.js.runtime.JSLinker.STRATEGY;
		try {
			hope.magic.js.runtime.JSLinker.STRATEGY = hope.magic.js.runtime.JSLinker.InvocationStrategy.SPREADER;
			JSContext cx = new JSContext();
			cx.set("target", new VoidTarget());
			Object res = cx.eval("target.doNothing();");
			System.out.println("[Bug 3.1 现象 SPREADER] res=" + res);
			Assertions.assertSame(JSUndefined.INSTANCE, res, "void method under SPREADER strategy must return undefined, not null!");
		} finally {
			hope.magic.js.runtime.JSLinker.STRATEGY = oldStrategy;
		}
	}

	public static class OverloadCtorTarget {
		public final String tag;

		public OverloadCtorTarget(int x) {
			this.tag = "int:" + x;
		}

		public OverloadCtorTarget(String s) {
			this.tag = "string:" + s;
		}
	}

	/**
	 * 验证构造函数重载解析与 CallSite 守卫锁定缺陷
	 */
	@Test
	public void testBug_ConstructorOverloadResolutionAndGuard() {
		JSContext cx = new JSContext();
		cx.set("OverloadCtorTarget", OverloadCtorTarget.class);
		String script = """
			function make(val) {
				return new OverloadCtorTarget(val);
			}
			let o1 = make(100);
			let o2 = make("hello");
			[o1.tag, o2.tag];
			""";
		cx.eval(script);
		Object tag1 = cx.eval("o1.tag;");
		Object tag2 = cx.eval("o2.tag;");
		System.out.println("[Constructor Overload 现象] tag1=" + tag1 + ", tag2=" + tag2);
		Assertions.assertEquals("int:100", tag1);
		Assertions.assertEquals("string:hello", tag2);
	}

	/**
	 * 验证缺陷 4.1: Java 数组 .length 访问未挂载 CallSite 守卫导致每次回退慢路径
	 */
	@Test
	public void testBug4_1_JavaArrayLengthCallSiteGuardMissing() throws Throwable {
		String[] arr = new String[]{"a", "b", "c"};
		java.lang.invoke.MethodType getterType = java.lang.invoke.MethodType.methodType(Object.class, Object.class);
		ChainedCallSite site = (ChainedCallSite) hope.magic.js.runtime.JSLinker.bootstrapGetProp(
			java.lang.invoke.MethodHandles.lookup(), "getProp", getterType, "length"
		);
		Assertions.assertEquals(0, site.getChainDepth(), "Initial chainDepth must be 0");
		Object len = site.getTarget().invokeExact((Object) arr);
		Assertions.assertEquals(3.0, ((Number) len).doubleValue());
		System.out.println("[Bug 4.1 现象] array length site.getChainDepth()=" + site.getChainDepth());
		Assertions.assertTrue(site.getChainDepth() > 0, "Array length CallSite must install Guard!");
	}

	/**
	 * 验证缺陷 4.2: Java 数组与 List 索引访问 (getIndexDynamicFallback) 未挂载 CallSite 守卫
	 */
	@Test
	public void testBug4_2_JavaCollectionAndArrayIndexGuardMissing() throws Throwable {
		java.lang.invoke.MethodType indexType = java.lang.invoke.MethodType.methodType(Object.class, Object.class, Object.class);

		// 1. 测试 List 索引
		java.util.List<String> list = java.util.List.of("x", "y", "z");
		ChainedCallSite listSite = (ChainedCallSite) hope.magic.js.runtime.JSLinker.bootstrapGetIndex(
			java.lang.invoke.MethodHandles.lookup(), "getIndex", indexType
		);
		Assertions.assertEquals(0, listSite.getChainDepth(), "Initial list site chainDepth must be 0");
		Object item = listSite.getTarget().invokeExact((Object) list, (Object) 1);
		Assertions.assertEquals("y", item);
		System.out.println("[Bug 4.2 现象] list index site.getChainDepth()=" + listSite.getChainDepth());
		Assertions.assertTrue(listSite.getChainDepth() > 0, "List index CallSite must install Guard!");

		// 2. 测试 Java 原生数组索引
		int[] intArr = new int[]{10, 20, 30};
		ChainedCallSite arrSite = (ChainedCallSite) hope.magic.js.runtime.JSLinker.bootstrapGetIndex(
			java.lang.invoke.MethodHandles.lookup(), "getIndex", indexType
		);
		Assertions.assertEquals(0, arrSite.getChainDepth(), "Initial arr site chainDepth must be 0");
		Object arrItem = arrSite.getTarget().invokeExact((Object) intArr, (Object) 2);
		Assertions.assertEquals(30.0, ((Number) arrItem).doubleValue());
		System.out.println("[Bug 4.2 现象] array index site.getChainDepth()=" + arrSite.getChainDepth());
		Assertions.assertTrue(arrSite.getChainDepth() > 0, "Array index CallSite must install Guard!");
	}
}
