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

	@Test
	public void testDeepPrototypeChainConstantFoldingAndIntermediateShadowing() throws Throwable {
		JSObject proto3 = new JSObject();
		proto3.put("deepVal", "from_p3");

		JSObject proto2 = new JSObject(proto3);
		JSObject proto1 = new JSObject(proto2);
		JSObject inst = new JSObject(proto1);

		MethodHandles.Lookup lookup = MethodHandles.lookup();
		MethodType type = MethodType.methodType(Object.class, Object.class);

		CallSite callSite = JSLinker.bootstrapGetProp(lookup, "deepVal", type, "deepVal");
		ChainedCallSite site = (ChainedCallSite) callSite;

		// 1. 跨 3 层原型链初次读取：应当折叠为常量并为 proto1, proto2, proto3 全部装载 SwitchPoint 保护
		Object got1 = site.dynamicInvoker().invokeExact((Object) inst);
		Assertions.assertEquals("from_p3", got1);
		Assertions.assertEquals(1, site.getChainDepth());

		SwitchPoint sp1 = proto1.getProtoSwitchPoint();
		SwitchPoint sp2 = proto2.getProtoSwitchPoint();
		SwitchPoint sp3 = proto3.getProtoSwitchPoint();
		Assertions.assertNotNull(sp1);
		Assertions.assertNotNull(sp2);
		Assertions.assertNotNull(sp3);

		// 2. 中间原型 proto2 进行属性遮蔽 (Shadowing)
		proto2.put("deepVal", "from_p2");
		Assertions.assertTrue(sp2.hasBeenInvalidated(), "Intermediate prototype SwitchPoint must be invalidated upon property addition");

		// 再次读取：中间原型 SwitchPoint 失效，Deopt 回退并自愈重链到 proto2
		Object got2 = site.dynamicInvoker().invokeExact((Object) inst);
		Assertions.assertEquals("from_p2", got2);
		Assertions.assertEquals(1, site.getChainDepth());

		// 3. 直接原型 proto1 再次遮蔽
		proto1.put("deepVal", "from_p1");
		Assertions.assertTrue(sp1.hasBeenInvalidated(), "Direct prototype SwitchPoint must be invalidated upon property addition");

		Object got3 = site.dynamicInvoker().invokeExact((Object) inst);
		Assertions.assertEquals("from_p1", got3);
		Assertions.assertEquals(1, site.getChainDepth());

		// 4. 实例自身写入自有属性遮蔽原型链
		inst.put("deepVal", "from_inst");
		Object got4 = site.dynamicInvoker().invokeExact((Object) inst);
		Assertions.assertEquals("from_inst", got4);
	}

	@Test
	public void testPrototypePrimitiveDoubleAndIntIC() throws Throwable {
		JSObject proto = new JSObject();
		proto.putDouble("gravity", 9.8);
		proto.putDouble("maxCount", 100.0);

		JSObject inst = new JSObject(proto);
		MethodHandles.Lookup lookup = MethodHandles.lookup();

		// A. 测试 bootstrapGetPropDouble 原型链 IC
		MethodType doubleType = MethodType.methodType(double.class, Object.class);
		CallSite doubleSite = JSLinker.bootstrapGetPropDouble(lookup, "gravity", doubleType, "gravity");
		ChainedCallSite cDoubleSite = (ChainedCallSite) doubleSite;

		double d1 = (double) cDoubleSite.dynamicInvoker().invokeExact((Object) inst);
		Assertions.assertEquals(9.8, d1, 0.0001);
		Assertions.assertEquals(1, cDoubleSite.getChainDepth());

		// 原型篡改
		proto.putDouble("gravity", 3.7);
		double d2 = (double) cDoubleSite.dynamicInvoker().invokeExact((Object) inst);
		Assertions.assertEquals(3.7, d2, 0.0001);
		Assertions.assertEquals(1, cDoubleSite.getChainDepth());

		// B. 测试 bootstrapGetPropInt 原型链 IC
		MethodType intType = MethodType.methodType(int.class, Object.class);
		CallSite intSite = JSLinker.bootstrapGetPropInt(lookup, "maxCount", intType, "maxCount");
		ChainedCallSite cIntSite = (ChainedCallSite) intSite;

		int i1 = (int) cIntSite.dynamicInvoker().invokeExact((Object) inst);
		Assertions.assertEquals(100, i1);
		Assertions.assertEquals(1, cIntSite.getChainDepth());

		// 原型篡改
		proto.putDouble("maxCount", 250.0);
		int i2 = (int) cIntSite.dynamicInvoker().invokeExact((Object) inst);
		Assertions.assertEquals(250, i2);
		Assertions.assertEquals(1, cIntSite.getChainDepth());
	}

	@Test
	public void testPrototypeDynamicIndexLookupIC() throws Throwable {
		JSObject proto = new JSObject();
		proto.put("theme", "dark");
		JSSymbol secretSym = new JSSymbol("secret");
		proto.put(secretSym, "classified");

		JSObject inst = new JSObject(proto);
		MethodHandles.Lookup lookup = MethodHandles.lookup();
		MethodType type = MethodType.methodType(Object.class, Object.class, Object.class);

		CallSite indexSite = JSLinker.bootstrapGetIndex(lookup, "getIndex", type);
		ChainedCallSite cIndexSite = (ChainedCallSite) indexSite;

		// 1. String Key 索引原型链查找
		Object v1 = cIndexSite.dynamicInvoker().invokeExact((Object) inst, (Object) "theme");
		Assertions.assertEquals("dark", v1);
		Assertions.assertEquals(1, cIndexSite.getChainDepth());

		// 篡改原型
		proto.put("theme", "light");
		Object v2 = cIndexSite.dynamicInvoker().invokeExact((Object) inst, (Object) "theme");
		Assertions.assertEquals("light", v2);
		Assertions.assertEquals(1, cIndexSite.getChainDepth());

		// 2. Symbol Key 索引原型链查找
		Object s1 = cIndexSite.dynamicInvoker().invokeExact((Object) inst, (Object) secretSym);
		Assertions.assertEquals("classified", s1);

		proto.put(secretSym, "top_secret");
		Object s2 = cIndexSite.dynamicInvoker().invokeExact((Object) inst, (Object) secretSym);
		Assertions.assertEquals("top_secret", s2);
	}

	@Test
	public void testProtectedSingletonMethodCallSiteSwitchPointAndDeopt() throws Throwable {
		JSContext cx = new JSContext();
		JSObject math = (JSObject) cx.eval("Math");
		MethodHandles.Lookup lookup = MethodHandles.lookup();
		MethodType type = MethodType.methodType(Object.class, Object.class, Object.class);

		CallSite callSite = JSLinker.bootstrapInvoke(lookup, "abs", type, "abs");
		ChainedCallSite site = (ChainedCallSite) callSite;

		// 1. 首次调用受保护单例方法 Math.abs(-42)
		Object res1 = site.dynamicInvoker().invokeExact((Object) math, (Object) (-42));
		Assertions.assertEquals(42.0, ((Number) res1).doubleValue(), 0.0001);
		Assertions.assertEquals(1, site.getChainDepth());

		SwitchPoint sp = math.getProtoSwitchPoint();
		Assertions.assertNotNull(sp);
		Assertions.assertFalse(sp.hasBeenInvalidated());

		// 2. 篡改单例方法 (Monkey patching Math.abs)
		math.put("abs", (JSFunction) (ctx, thisObj, args) -> 999.0);
		Assertions.assertTrue(sp.hasBeenInvalidated(), "SwitchPoint must be invalidated when Math is monkey-patched");

		// 3. 再次调用：触发 Deopt 并执行新方法
		Object res2 = site.dynamicInvoker().invokeExact((Object) math, (Object) (-42));
		Assertions.assertEquals(999.0, ((Number) res2).doubleValue(), 0.0001);
		Assertions.assertEquals(1, site.getChainDepth(), "Site should self-heal and reset depth to 1");
	}

	@Test
	public void testPlainInstanceOwnMethodInlineCache() throws Throwable {
		MethodHandles.Lookup lookup = MethodHandles.lookup();
		MethodType type = MethodType.methodType(Object.class, Object.class);

		CallSite callSite = JSLinker.bootstrapInvoke(lookup, "getX", type, "getX");
		ChainedCallSite site = (ChainedCallSite) callSite;

		JSObject p1 = new JSObject();
		p1.put("x", 10);
		p1.put("getX", (JSFunction) (cx, thisObj, args) -> ((JSObject) thisObj).get("x"));

		// 1. 调用 p1.getX()，安装自有方法 Shape IC
		Object res1 = site.dynamicInvoker().invokeExact((Object) p1);
		Assertions.assertEquals(10.0, ((Number) res1).doubleValue(), 0.0001);
		Assertions.assertEquals(1, site.getChainDepth());

		// 2. 具有相同 Shape 的另一个实例 p2
		JSObject p2 = new JSObject();
		p2.put("x", 20);
		p2.put("getX", (JSFunction) (cx, thisObj, args) -> ((JSObject) thisObj).get("x"));

		// 验证 shape 相同
		Assertions.assertEquals(p1.shape, p2.shape);

		// 调用 p2.getX()：命中 Shape IC，无需重新链接，零额外开销
		Object res2 = site.dynamicInvoker().invokeExact((Object) p2);
		Assertions.assertEquals(20.0, ((Number) res2).doubleValue(), 0.0001);
		Assertions.assertEquals(1, site.getChainDepth(), "Shape IC should hit without incrementing chain depth");

		// 3. 将 p1.getX 替换为其它函数或删除
		p1.put("getX", (JSFunction) (cx, thisObj, args) -> 888);
		Object res3 = site.dynamicInvoker().invokeExact((Object) p1);
		Assertions.assertEquals(888.0, ((Number) res3).doubleValue(), 0.0001);
	}

	@Test
	public void testDirectFunctionCallMonomorphicAndPolymorphic() throws Throwable {
		MethodHandles.Lookup lookup = MethodHandles.lookup();
		MethodType type = MethodType.methodType(Object.class, Object.class, Object.class);

		CallSite callSite = JSLinker.bootstrapInvoke(lookup, "$invoke$", type, "$invoke$");
		ChainedCallSite site = (ChainedCallSite) callSite;

		JSFunction fn1 = (cx, thisObj, args) -> ((int) args[0]) + 1;
		JSFunction fn2 = (cx, thisObj, args) -> ((int) args[0]) * 2;

		// 1. 单态调用：首个函数实例绑定 MH_IS_SAME_OBJECT 常量
		Object res1 = site.dynamicInvoker().invokeExact((Object) fn1, (Object) 5);
		Assertions.assertEquals(6, res1);
		Assertions.assertEquals(1, site.getChainDepth());

		// 再次调用相同函数
		Object res2 = site.dynamicInvoker().invokeExact((Object) fn1, (Object) 10);
		Assertions.assertEquals(11, res2);
		Assertions.assertEquals(1, site.getChainDepth());

		// 2. 多态调用：切换不同函数实例，演进到多态保护
		Object res3 = site.dynamicInvoker().invokeExact((Object) fn2, (Object) 10);
		Assertions.assertEquals(20, res3);
		Assertions.assertEquals(2, site.getChainDepth());

		// 两个不同函数均可正确调用
		Assertions.assertEquals(8, site.dynamicInvoker().invokeExact((Object) fn1, (Object) 7));
		Assertions.assertEquals(14, site.dynamicInvoker().invokeExact((Object) fn2, (Object) 7));
	}

	@Test
	public void testDeleteDoublePropertyJITInliningBug() {
		JSContext cx = new JSContext();
		String script = """
			function compute(p) {
				return p.x + 1;
			}
			let p = { x: 10.0 };
			for (let i = 0; i < 20000; i++) {
				compute(p);
			}
			delete p.x;
			compute(p);
		""";
		Object res = cx.eval(script);
		System.out.println("Result of compute(p) after delete: " + res);
		Assertions.assertTrue(res instanceof Double && Double.isNaN((Double) res), "Result should be NaN, but was: " + res);
	}

	// -----------------------------------------------------------------------
	// P5: Symbol.species protector tests
	// -----------------------------------------------------------------------

	/**
	 * Baseline: when nobody overrides Array[Symbol.species], map/filter/slice/concat/flat
	 * should all return plain JSArray instances (fast path active).
	 */
	@Test
	public void testArrayMethodsFastPathWhenSpeciesUntouched() {
		JSContext cx = new JSContext();
		String script = """
			let arr = [1, 2, 3];
			let mapped  = arr.map(x => x * 2);
			let filtered = arr.filter(x => x > 1);
			let sliced   = arr.slice(1);
			let concatted = arr.concat([4]);
			let flatted   = [[1],[2]].flat();
			[
			  mapped.join(","),
			  filtered.join(","),
			  sliced.join(","),
			  concatted.join(","),
			  flatted.join(",")
			].join("|")
			""";
		Object res = cx.eval(script);
		Assertions.assertEquals("2,4,6|2,3|2,3|1,2,3,4|1,2", res,
			"All array methods should return correct values on fast path");
	}

	/**
	 * Overriding Array[Symbol.species] must invalidate the species protector SwitchPoint.
	 * After invalidation, hasBeenInvalidated() must return true.
	 */
	@Test
	public void testArraySpeciesProtectorInvalidatedOnOverride() {
		// Grab the current SwitchPoint before any modification
		java.lang.invoke.SwitchPoint spBefore = BuiltinProtector.getArraySpeciesSwitchPoint();
		Assertions.assertFalse(spBefore.hasBeenInvalidated(), "Species SP must start valid");

		// Force invalidation (as would happen when user mutates Array[Symbol.species])
		BuiltinProtector.invalidateArraySpeciesProtector();

		Assertions.assertTrue(spBefore.hasBeenInvalidated(), "Species SP must be invalidated after explicit call");

		// Reset for other tests
		BuiltinProtector.resetAll();
		Assertions.assertFalse(BuiltinProtector.getArraySpeciesSwitchPoint().hasBeenInvalidated(),
			"Species SP must be fresh after resetAll()");
	}

	/**
	 * Full end-to-end: set Array[Symbol.species] to a subclass constructor,
	 * then call [1,2,3].map(...) and verify the protector was invalidated.
	 * (The species slow-path kicks in when isArraySpeciesValid() == false.)
	 */
	@Test
	public void testArraySpeciesOverrideInvalidatesProtector() {
		JSContext cx = new JSContext();
		// First reset to get a fresh SP
		BuiltinProtector.resetAll();
		java.lang.invoke.SwitchPoint sp = BuiltinProtector.getArraySpeciesSwitchPoint();
		Assertions.assertFalse(sp.hasBeenInvalidated(), "Must start valid");

		// This mutation (writing Symbol.species on the Array constructor) should
		// trigger onStructuralOrPropertyChange on LazyArray.ARRAY and invalidate the SP.
		cx.eval("""
			// Override Array[Symbol.species] on the constructor object
			Object.defineProperty(Array, Symbol.species, {
			  get: function() { return Array; }
			});
			""");

		// The SP should now be invalidated
		Assertions.assertTrue(sp.hasBeenInvalidated(),
			"arraySpeciesSwitchPoint must be invalidated after Array[Symbol.species] override");

		// map should still produce correct results (falls back to slow spec path)
		Object result = cx.eval("[1,2,3].map(x => x + 10).join(',')");
		Assertions.assertEquals("11,12,13", result,
			"map must still produce correct results even after species override");

		// Reset for other tests
		BuiltinProtector.resetAll();
	}
}

