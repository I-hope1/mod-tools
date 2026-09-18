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

	private static Object extractBoundInvoker(MethodHandle mh) {
		Class<?> cur = mh.getClass();
		while (cur != null && cur != Object.class) {
			for (Field f : cur.getDeclaredFields()) {
				try {
					f.setAccessible(true);
					Object val = f.get(mh);
					if (val instanceof hope.magic.js.runtime.MagicJIT.MagicInvoker || val instanceof hope.magic.js.runtime.MagicJIT.MagicConstructorInvoker) {
						return val;
					}
				} catch (Throwable ignored) {}
			}
			cur = cur.getSuperclass();
		}
		return null;
	}

	/**
	 * 验证缺陷 5.1: MagicJIT.createExactMethodStub 与 createExactConstructorStub 直连字节码调用器
	 */
	@Test
	public void testBug5_ExactMethodAndConstructorStubUsesMagicInvoker() throws Throwable {
		// 1. Method stub 直连 MagicInvoker 验证
		java.lang.reflect.Method method = OverloadTarget.class.getMethod("execute", int.class);
		MethodHandle stub = hope.magic.js.runtime.MagicJIT.createExactMethodStub(OverloadTarget.class, method);
		Assertions.assertNotNull(stub, "Exact stub must not be null");

		OverloadTarget target = new OverloadTarget();
		Object res = stub.invokeExact((Object) target, (Object) 42);
		Assertions.assertEquals("int:42", res);

		Object boundInvoker = extractBoundInvoker(stub);
		System.out.println("[Bug 5 现象 Method] boundInvoker=" + boundInvoker);
		Assertions.assertNotNull(boundInvoker, "createExactMethodStub must bind a MagicInvoker bytecode invoker instance!");

		// 2. Constructor stub 直连 MagicConstructorInvoker 验证
		java.lang.reflect.Constructor<?> ctor = SimpleConstructorTarget.class.getConstructor();
		MethodHandle ctorStub = hope.magic.js.runtime.MagicJIT.createExactConstructorStub(SimpleConstructorTarget.class, ctor);
		Assertions.assertNotNull(ctorStub, "Constructor stub must not be null");

		Object instance = ctorStub.invokeExact((Object) SimpleConstructorTarget.class);
		Assertions.assertInstanceOf(SimpleConstructorTarget.class, instance);

		Object boundCtorInvoker = extractBoundInvoker(ctorStub);
		System.out.println("[Bug 5 现象 Constructor] boundCtorInvoker=" + boundCtorInvoker);
		Assertions.assertNotNull(boundCtorInvoker, "createExactConstructorStub must bind a MagicConstructorInvoker bytecode invoker instance!");
	}

	public static class NumericBeanTarget {
		private double price = 0.0;
		public double amount = 0.0;

		public double getPrice() {
			return price;
		}

		public void setPrice(double price) {
			this.price = price;
		}
	}

	/**
	 * 验证缺陷 6: JavaBean Setter 与 Java Field 在 setPropDoubleFallback 中完全漏装 CallSite 守卫
	 */
	@Test
	public void testBug6_JavaBeanAndFieldDoubleSetterCallSiteGuardMissing() throws Throwable {
		NumericBeanTarget bean = new NumericBeanTarget();

		// 1. 测试 JavaBean double setter
		java.lang.invoke.MethodType setterType = java.lang.invoke.MethodType.methodType(void.class, Object.class, double.class);
		ChainedCallSite propSite = (ChainedCallSite) hope.magic.js.runtime.JSLinker.bootstrapSetPropDouble(
			java.lang.invoke.MethodHandles.lookup(), "setProp", setterType, "price"
		);
		Assertions.assertEquals(0, propSite.getChainDepth(), "Initial chainDepth must be 0");
		propSite.getTarget().invokeExact((Object) bean, 99.5);
		Assertions.assertEquals(99.5, bean.getPrice());
		System.out.println("[Bug 6 现象 Setter] propSite.getChainDepth()=" + propSite.getChainDepth());
		Assertions.assertTrue(propSite.getChainDepth() > 0, "JavaBean double setter CallSite must install Guard in setPropDoubleFallback!");

		// 2. 测试 Java double field
		ChainedCallSite fieldSite = (ChainedCallSite) hope.magic.js.runtime.JSLinker.bootstrapSetPropDouble(
			java.lang.invoke.MethodHandles.lookup(), "setProp", setterType, "amount"
		);
		Assertions.assertEquals(0, fieldSite.getChainDepth(), "Initial chainDepth must be 0");
		fieldSite.getTarget().invokeExact((Object) bean, 123.45);
		Assertions.assertEquals(123.45, bean.amount);
		System.out.println("[Bug 6 现象 Field] fieldSite.getChainDepth()=" + fieldSite.getChainDepth());
		Assertions.assertTrue(fieldSite.getChainDepth() > 0, "Java field double setter CallSite must install Guard in setPropDoubleFallback!");
	}

	public static class ActionTarget {
		public boolean executed = false;

		public void execute() {
			executed = true;
		}

		public String getInfo() {
			return "valid-info";
		}
	}

	public record SampleRecord(String title, int count) {}

	/**
	 * 验证缺陷 7: MethodResolver.findGetterMethod 将无参非 Getter 方法（如 void execute()）误判为 Getter，
	 * 导致属性读取时被立即副作用执行，且无法获取为 JSFunction 方法引用。
	 * 修复后：普通类的 void/非 getter 方法被正确解析为 JSFunction；Record 类的 component accessor 和 JavaBean 的 get/is 方法正常作为 Getter 工作。
	 */
	@Test
	public void testBug7_VoidOrRegularMethodMistakenAsGetter() throws Throwable {
		ActionTarget target = new ActionTarget();

		java.lang.invoke.MethodType getterType = java.lang.invoke.MethodType.methodType(Object.class, Object.class);
		ChainedCallSite site = (ChainedCallSite) hope.magic.js.runtime.JSLinker.bootstrapGetProp(
			java.lang.invoke.MethodHandles.lookup(), "getProp", getterType, "execute"
		);

		// 1. 仅仅读取属性 "execute"，不应立即执行 void execute() 方法！
		Object propVal = site.getTarget().invokeExact((Object) target);
		System.out.println("[Bug 7 现象] target.executed=" + target.executed + ", propVal=" + propVal);

		Assertions.assertFalse(target.executed, "Accessing property 'execute' must NOT invoke the void execute() method!");
		Assertions.assertInstanceOf(hope.magic.js.runtime.JSFunction.class, propVal, "Property 'execute' must resolve to a callable JSFunction!");

		// 2. 调用返回的 JSFunction 时，才真正执行方法
		((hope.magic.js.runtime.JSFunction) propVal).call(new hope.magic.js.runtime.JSContext(), target, new Object[0]);
		Assertions.assertTrue(target.executed, "Calling the returned JSFunction must invoke execute()!");

		// 3. 验证标准 JavaBean getter 正常工作
		ChainedCallSite infoSite = (ChainedCallSite) hope.magic.js.runtime.JSLinker.bootstrapGetProp(
			java.lang.invoke.MethodHandles.lookup(), "getProp", getterType, "info"
		);
		Object infoVal = infoSite.getTarget().invokeExact((Object) target);
		Assertions.assertEquals("valid-info", infoVal);

		// 4. 验证 Record 类组件属性正常作为 getter 工作
		SampleRecord record = new SampleRecord("hello-record", 42);
		ChainedCallSite recordSite = (ChainedCallSite) hope.magic.js.runtime.JSLinker.bootstrapGetProp(
			java.lang.invoke.MethodHandles.lookup(), "getProp", getterType, "title"
		);
		Object titleVal = recordSite.getTarget().invokeExact((Object) record);
		Assertions.assertEquals("hello-record", titleVal);
	}

	public static class IndexedBean {
		public String name;
		private int count;

		public int getCount() {
			return count;
		}

		public void setCount(int count) {
			this.count = count;
		}
	}

	/**
	 * 验证缺陷 8: JSLinker.getIndex 与 setIndex 对 Java Map 与通用 Java Bean 的动态索引读写缺失：
	 * 1. getIndex(target, Object index) 漏掉 Map 检索与 JavaBean 属性获取，导致 map["key"] 与 bean["prop"] 返回 undefined；
	 * 2. setIndex(target, Object index, value) 漏掉 JavaBean 属性写入，导致 bean["prop"] = val 静默丢失。
	 */
	@Test
	public void testBug8_DynamicIndexAccessOnMapAndJavaBean() {
		// 1. 测试 Java Map 字符串键动态读写
		java.util.Map<String, Object> map = new java.util.HashMap<>();
		map.put("city", "Beijing");
		Object mapVal = hope.magic.js.runtime.JSLinker.getIndex(map, "city");
		System.out.println("[Bug 8 现象 Map Read] getIndex(map, 'city')=" + mapVal);
		Assertions.assertEquals("Beijing", mapVal, "getIndex on Map with String key must return the map entry!");

		// 2. 测试 JavaBean 动态索引属性读取
		IndexedBean bean = new IndexedBean();
		bean.name = "MyBean";
		bean.setCount(100);

		Object nameVal = hope.magic.js.runtime.JSLinker.getIndex(bean, "name");
		System.out.println("[Bug 8 现象 Bean Field Read] getIndex(bean, 'name')=" + nameVal);
		Assertions.assertEquals("MyBean", nameVal, "getIndex on JavaBean field property must return field value!");

		Object countVal = hope.magic.js.runtime.JSLinker.getIndex(bean, "count");
		System.out.println("[Bug 8 现象 Bean Getter Read] getIndex(bean, 'count')=" + countVal);
		Assertions.assertEquals(100, countVal, "getIndex on JavaBean getter property must return getter value!");

		// 3. 测试 JavaBean 动态索引属性写入
		hope.magic.js.runtime.JSLinker.setIndex(bean, "name", "UpdatedName");
		System.out.println("[Bug 8 现象 Bean Field Write] bean.name=" + bean.name);
		Assertions.assertEquals("UpdatedName", bean.name, "setIndex on JavaBean field must update field!");

		hope.magic.js.runtime.JSLinker.setIndex(bean, "count", 200);
		System.out.println("[Bug 8 现象 Bean Setter Write] bean.count=" + bean.getCount());
		Assertions.assertEquals(200, bean.getCount(), "setIndex on JavaBean setter must update value!");
	}

	/**
	 * 验证缺陷 9: 静态方法调用的 CallSite 守卫错误使用 target.getClass() == expected (检查到 Class.class == Math.class -> false)，
	 * 导致单态守卫对静态方法永远匹配失败，每次调用都重复触发 fallback 增加链深，最终退化为巨态 (Megamorphic) 慢路径。
	 */
	@Test
	public void testBug9_StaticMethodCallSiteGuardAlwaysFails() throws Throwable {
		java.lang.invoke.MethodType type = java.lang.invoke.MethodType.methodType(Object.class, Object.class, Object.class, Object.class);
		ChainedCallSite site = (ChainedCallSite) hope.magic.js.runtime.JSLinker.bootstrapInvoke(
			java.lang.invoke.MethodHandles.lookup(), "invoke", type, "max"
		);
		Assertions.assertEquals(0, site.getChainDepth(), "Initial chainDepth must be 0");

		// 第一次调用 Math.max(10.0, 20.0)：应当安装单态守卫，chainDepth 变为 1
		Object r1 = site.getTarget().invokeExact((Object) Math.class, (Object) 10.0, (Object) 20.0);
		Assertions.assertEquals(20.0, r1);
		int depthAfterFirstCall = site.getChainDepth();
		System.out.println("[Bug 9 现象] depthAfterFirstCall=" + depthAfterFirstCall);
		Assertions.assertEquals(1, depthAfterFirstCall, "chainDepth must be 1 after first call");

		// 第二次调用相同的静态方法 Math.max(30.0, 40.0)：
		// 若守卫正确生效，应当直接命中快速路径，chainDepth 仍然保持为 1！
		// 缺陷现象：由于 isExactClass 比较 target.getClass() 即 Class.class == Math.class 永远为 false，守卫失效再次进入 fallback，chainDepth 异常递增为 2！
		Object r2 = site.getTarget().invokeExact((Object) Math.class, (Object) 30.0, (Object) 40.0);
		Assertions.assertEquals(40.0, r2);
		int depthAfterSecondCall = site.getChainDepth();
		System.out.println("[Bug 9 现象] depthAfterSecondCall=" + depthAfterSecondCall);
		Assertions.assertEquals(1, depthAfterSecondCall, "Guard must succeed on same static class and NOT re-enter fallback (depth must stay 1)!");
	}
}

