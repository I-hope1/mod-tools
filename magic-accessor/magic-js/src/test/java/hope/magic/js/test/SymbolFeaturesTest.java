package hope.magic.js.test;

import hope.magic.js.runtime.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.List;

public class SymbolFeaturesTest {

	@Test
	public void testWellKnownSymbolsFixedIds() {
		Assertions.assertEquals(0, JSSymbol.ID_ITERATOR);
		Assertions.assertEquals(0, JSSymbol.ITERATOR.getSymbolId());
		Assertions.assertEquals(1, JSSymbol.ASYNC_ITERATOR.getSymbolId());
		Assertions.assertEquals(2, JSSymbol.TO_STRING_TAG.getSymbolId());
		Assertions.assertEquals(3, JSSymbol.HAS_INSTANCE.getSymbolId());
		Assertions.assertEquals(4, JSSymbol.IS_CONCAT_SPREADABLE.getSymbolId());
		Assertions.assertEquals(5, JSSymbol.SPECIES.getSymbolId());
		Assertions.assertEquals(6, JSSymbol.TO_PRIMITIVE.getSymbolId());
		Assertions.assertEquals(7, JSSymbol.UNSCOPABLES.getSymbolId());
		Assertions.assertEquals(8, JSSymbol.MATCH.getSymbolId());
		Assertions.assertEquals(9, JSSymbol.REPLACE.getSymbolId());
		Assertions.assertEquals(10, JSSymbol.SEARCH.getSymbolId());
		Assertions.assertEquals(11, JSSymbol.SPLIT.getSymbolId());

		// Verify SymbolTable pre-registered lookups
		Assertions.assertEquals(0, SymbolTable.id(JSSymbol.ITERATOR.getKey()));
		Assertions.assertEquals(JSSymbol.ITERATOR.getKey(), SymbolTable.name(0));
		Assertions.assertEquals(JSSymbol.ITERATOR, JSSymbol.fromKey(JSSymbol.ITERATOR.getKey()));
		Assertions.assertTrue(JSSymbol.ITERATOR.isWellKnown());

		// Dynamic Symbol allocation must start from >= 12
		JSSymbol dyn = new JSSymbol("custom");
		Assertions.assertFalse(dyn.isWellKnown());
		Assertions.assertTrue(dyn.getSymbolId() >= 12);
	}

	@Test
	public void testDynamicSymbolGarbageCollection() {
		WeakReference<JSSymbol> weakRef = createEphemeralSymbol();
		// Suggest GC to collect unreferenced ephemeral symbol
		for (int i = 0; i < 5; i++) {
			System.gc();
			if (weakRef.get() == null) break;
			try { Thread.sleep(20); } catch (InterruptedException ignored) {}
		}
		// The ephemeral symbol should not be pinned by any static strong map
		Assertions.assertNull(weakRef.get(), "Ephemeral JSSymbol should be garbage collected");
	}

	private WeakReference<JSSymbol> createEphemeralSymbol() {
		JSSymbol sym = new JSSymbol("ephemeral_temp");
		return new WeakReference<>(sym);
	}

	@Test
	public void testSymbolObjectRetentionAndFastSlotAccess() {
		JSContext cx = new JSContext();
		String code = """
			function createObj() {
				const sym = Symbol("secret");
				const obj = {};
				obj[sym] = 42;
				return obj;
			}
			const o = createObj();
			const syms = Object.getOwnPropertySymbols(o);
			[syms.length, o[syms[0]], syms[0].description];
		""";
		Object res = cx.eval(code);
		Assertions.assertTrue(res instanceof JSArray);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals(1, ((Number) arr.getElement(0)).intValue());
		Assertions.assertEquals(42, ((Number) arr.getElement(1)).intValue());
		Assertions.assertEquals("secret", arr.getElement(2));
	}

	@Test
	public void testObjectLiteralComputedProperties() {
		JSContext cx = new JSContext();
		String code = """
			const s1 = Symbol("prop1");
			const s2 = Symbol("method2");
			const obj = {
				[s1]: 100,
				["dynamic" + "Key"]: 200,
				[s2](x) {
					return this[s1] + x;
				},
				get [Symbol.for("g1")]() {
					return 300;
				}
			};
			[obj[s1], obj.dynamicKey, obj[s2](50), obj[Symbol.for("g1")]];
		""";
		Object res = cx.eval(code);
		Assertions.assertTrue(res instanceof JSArray);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals(100, ((Number) arr.getElement(0)).intValue());
		Assertions.assertEquals(200, ((Number) arr.getElement(1)).intValue());
		Assertions.assertEquals(150, ((Number) arr.getElement(2)).intValue());
		Assertions.assertEquals(300, ((Number) arr.getElement(3)).intValue());
	}

	@Test
	public void testStrictImplicitConversion() {
		JSContext cx = new JSContext();
		// Explicit String(sym) and sym.toString() must work
		Object res1 = cx.eval("const s = Symbol('hello'); [String(s), s.toString(), typeof s];");
		Assertions.assertTrue(res1 instanceof JSArray);
		JSArray arr = (JSArray) res1;
		Assertions.assertEquals("Symbol(hello)", arr.getElement(0));
		Assertions.assertEquals("Symbol(hello)", arr.getElement(1));
		Assertions.assertEquals("symbol", arr.getElement(2));

		// Implicit string concat: sym + "" -> TypeError
		Assertions.assertThrows(RuntimeException.class, () -> cx.eval("const s = Symbol('err'); s + '';"));
		// Implicit string concat: "" + sym -> TypeError
		Assertions.assertThrows(RuntimeException.class, () -> cx.eval("const s = Symbol('err'); '' + s;"));
		// Implicit arithmetic: sym + 1 -> TypeError
		Assertions.assertThrows(RuntimeException.class, () -> cx.eval("const s = Symbol('err'); s + 1;"));
		// Implicit arithmetic: 1 + sym -> TypeError
		Assertions.assertThrows(RuntimeException.class, () -> cx.eval("const s = Symbol('err'); 1 + s;"));
		// Unary +sym -> TypeError
		Assertions.assertThrows(RuntimeException.class, () -> cx.eval("const s = Symbol('err'); +s;"));
		// Unary -sym -> TypeError
		Assertions.assertThrows(RuntimeException.class, () -> cx.eval("const s = Symbol('err'); -s;"));
		// Bitwise ~sym -> TypeError
		Assertions.assertThrows(RuntimeException.class, () -> cx.eval("const s = Symbol('err'); ~s;"));
	}

	@Test
	public void testSymbolToPrimitive() {
		JSContext cx = new JSContext();
		String code = """
			const obj = {
				val: 42,
				[Symbol.toPrimitive](hint) {
					if (hint === "number") return this.val;
					if (hint === "string") return "str_" + this.val;
					return "def_" + this.val;
				}
			};
			[+obj, "" + obj];
		""";
		Object res = cx.eval(code);
		Assertions.assertTrue(res instanceof JSArray);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals(42.0, ((Number) arr.getElement(0)).doubleValue(), 1e-6);
		Assertions.assertEquals("def_42", arr.getElement(1));
	}

	@Test
	public void testSpecializedICGuards() {
		JSContext cx = new JSContext();
		String code = """
			const s1 = Symbol("s1");
			const s2 = Symbol("s2");
			const obj = { [s1]: 10, [s2]: 20, a: 30, b: 40 };

			let sum = 0;
			for (let i = 0; i < 1000; i++) {
				sum += obj[s1] + obj[s2] + obj.a + obj.b;
			}
			sum;
		""";
		Object res = cx.eval(code);
		Assertions.assertEquals(100000.0, ((Number) res).doubleValue(), 1e-6);
	}

	@Test
	public void testCustomSymbolIteratorForOf() {
		JSContext cx = new JSContext();
		String code = """
			const myIterable = {
				[Symbol.iterator]() {
					let i = 0;
					return {
						next() {
							if (i < 3) return { value: ++i * 10, done: false };
							return { value: undefined, done: true };
						}
					};
				}
			};
			let sum = 0;
			for (const x of myIterable) {
				sum += x;
			}
			sum;
		""";
		Object res = cx.eval(code);
		Assertions.assertEquals(60.0, ((Number) res).doubleValue(), 1e-6);
	}

	@Test
	public void testStringForOf() {
		JSContext cx = new JSContext();
		String code = """
			let res = [];
			for (const ch of "hello") {
				res.push(ch);
			}
			res.join("-");
		""";
		Object res = cx.eval(code);
		Assertions.assertEquals("h-e-l-l-o", res);
	}

	@Test
	public void testNotIterableThrowsTypeError() {
		JSContext cx = new JSContext();
		Assertions.assertThrows(RuntimeException.class, () -> {
			cx.eval("for (const x of null) {}");
		});
		Assertions.assertThrows(RuntimeException.class, () -> {
			cx.eval("for (const x of undefined) {}");
		});
		Assertions.assertThrows(RuntimeException.class, () -> {
			cx.eval("for (const x of {}) {}");
		});
	}
}
