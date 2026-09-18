package hope.magic.js.test;

import hope.magic.js.runtime.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.SwitchPoint;

public class JSObjectPrototypeSwitchPointTest {

	@Test
	public void testPrototypeMethodCallSiteInvalidationAndRelink() throws Throwable {
		JSObject proto = new JSObject();
		proto.put("sayHello", (JSFunction) (cx, thisObj, args) -> "hello v1");

		JSObject inst = new JSObject(proto);
		MethodHandles.Lookup lookup = MethodHandles.lookup();
		MethodType type = MethodType.methodType(Object.class, Object.class);

		CallSite callSite = JSLinker.bootstrapInvoke(lookup, "sayHello", type, "sayHello");
		ChainedCallSite site = (ChainedCallSite) callSite;

		// 首次调用：绑定原型方法，挂载 protoSwitchPoint
		Object res1 = site.dynamicInvoker().invokeExact((Object) inst);
		Assertions.assertEquals("hello v1", res1);
		Assertions.assertEquals(1, site.getChainDepth());

		SwitchPoint oldSp = proto.getProtoSwitchPoint();
		Assertions.assertNotNull(oldSp);
		Assertions.assertFalse(oldSp.hasBeenInvalidated());

		// 篡改/更新原型方法
		proto.put("sayHello", (JSFunction) (cx, thisObj, args) -> "hello v2");
		Assertions.assertTrue(oldSp.hasBeenInvalidated(), "Old SwitchPoint must be invalidated when prototype is mutated");

		// 再次调用：触发 Deopt 并自愈重置回深度 1
		Object res2 = site.dynamicInvoker().invokeExact((Object) inst);
		Assertions.assertEquals("hello v2", res2);
		Assertions.assertEquals(1, site.getChainDepth(), "Site should self-heal and reset depth to 1");
	}

	@Test
	public void testPrototypePropertyGetInvalidation() throws Throwable {
		JSObject proto = new JSObject();
		JSFunction fn1 = (cx, thisObj, args) -> "v1";
		proto.put("myFunc", fn1);

		JSObject inst = new JSObject(proto);
		MethodHandles.Lookup lookup = MethodHandles.lookup();
		MethodType type = MethodType.methodType(Object.class, Object.class);

		CallSite callSite = JSLinker.bootstrapGetProp(lookup, "myFunc", type, "myFunc");
		ChainedCallSite site = (ChainedCallSite) callSite;

		Object got1 = site.dynamicInvoker().invokeExact((Object) inst);
		Assertions.assertSame(fn1, got1);
		Assertions.assertEquals(1, site.getChainDepth());

		// 更新原型属性
		JSFunction fn2 = (cx, thisObj, args) -> "v2";
		proto.put("myFunc", fn2);

		Object got2 = site.dynamicInvoker().invokeExact((Object) inst);
		Assertions.assertSame(fn2, got2);
		Assertions.assertEquals(1, site.getChainDepth());
	}

	@Test
	public void testSetPrototypeInvalidation() throws Throwable {
		JSObject protoA = new JSObject();
		protoA.put("whoAmI", (JSFunction) (cx, thisObj, args) -> "I am A");

		JSObject protoB = new JSObject();
		protoB.put("whoAmI", (JSFunction) (cx, thisObj, args) -> "I am B");

		JSObject inst = new JSObject(protoA);
		MethodHandles.Lookup lookup = MethodHandles.lookup();
		MethodType type = MethodType.methodType(Object.class, Object.class);

		CallSite callSite = JSLinker.bootstrapInvoke(lookup, "whoAmI", type, "whoAmI");
		ChainedCallSite site = (ChainedCallSite) callSite;

		Object res1 = site.dynamicInvoker().invokeExact((Object) inst);
		Assertions.assertEquals("I am A", res1);

		// 动态更换原型
		inst.setPrototype(protoB);

		// 再次调用，应当识别到原型变化并调用 protoB 的方法
		Object res2 = site.dynamicInvoker().invokeExact((Object) inst);
		Assertions.assertEquals("I am B", res2);
	}

	@Test
	public void testFullJSScriptPrototypeMonkeyPatching() {
		JSContext cx = new JSContext();
		String script = """
			function Person(name) {
				this.name = name;
			}
			Person.prototype.greet = function() {
				return "hi " + this.name;
			};
			var p = new Person("Alice");
			var r1 = p.greet();
			
			// 动态猴子补丁 (Monkey-patching prototype)
			Person.prototype.greet = function() {
				return "hello " + this.name;
			};
			var r2 = p.greet();
			
			r1 + " | " + r2;
		""";
		Object result = cx.eval(script);
		Assertions.assertEquals("hi Alice | hello Alice", result);
	}
}
