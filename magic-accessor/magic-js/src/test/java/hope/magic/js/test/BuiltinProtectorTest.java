package hope.magic.js.test;

import hope.magic.js.runtime.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BuiltinProtectorTest {

	private static Object originalPush;
	private static Object originalIterator;

	@BeforeAll
	public static void saveOriginalPrototypes() {
		originalPush = JSContext.LazyBuiltins.ARRAY_PROTOTYPE.get("push");
		originalIterator = JSContext.LazyBuiltins.ARRAY_PROTOTYPE.get(JSSymbol.ITERATOR);
	}

	@BeforeEach
	@AfterEach
	public void resetProtectorState() {
		if (originalPush != null) {
			JSContext.LazyBuiltins.ARRAY_PROTOTYPE.put("push", originalPush);
		}
		if (originalIterator != null) {
			JSContext.LazyBuiltins.ARRAY_PROTOTYPE.put(JSSymbol.ITERATOR, originalIterator);
		}
		BuiltinProtector.resetAll();
	}

	@Test
	public void testArrayProtoProtectorValidityAndInvalidation() {
		Assertions.assertTrue(BuiltinProtector.isArrayProtoValid(), "ArrayProtoProtector should be initially valid");

		// 执行原生 push 测试
		JSContext cx = new JSContext();
		Object res1 = cx.eval("""
			var arr = [1, 2];
			arr.push(3);
			arr.length;
		""");
		Assertions.assertEquals(3.0, ((Number) res1).doubleValue());

		// 篡改 Array.prototype.push
		cx.eval("""
			Array.prototype.push = function(x) {
				return 999;
			};
		""");

		Assertions.assertFalse(BuiltinProtector.isArrayProtoValid(), "ArrayProtoProtector must invalidate after modifying Array.prototype.push");

		// 再次调用 push，应当调用篡改后的函数返回 999
		Object res2 = cx.eval("""
			var arr2 = [10];
			arr2.push(20);
		""");
		Assertions.assertEquals(999.0, ((Number) res2).doubleValue());
	}

	@Test
	public void testArrayLengthIC() {
		JSContext cx = new JSContext();
		Object res = cx.eval("""
			var arr = [10, 20, 30, 40, 50];
			var totalLen = 0;
			for (var i = 0; i < 100; i++) {
				totalLen += arr.length;
			}
			totalLen;
		""");
		Assertions.assertEquals(500.0, ((Number) res).doubleValue());
	}

	@Test
	public void testDestructuringArrayOutOfBounds() {
		JSContext cx = new JSContext();
		Object res = cx.eval("""
			var arr = [5];
			var [ m = 1, n = 99 ] = arr;
			m + n;
		""");
		Assertions.assertEquals(104.0, ((Number) res).doubleValue());
	}

	@Test
	public void testArrayFastPushAndPop() {
		JSContext cx = new JSContext();
		Object res = cx.eval("""
			var arr = [];
			for (var i = 0; i < 10; i++) {
				arr.push(i);
			}
			var popped = arr.pop();
			popped + " | " + arr.length;
		""");
		Assertions.assertEquals("9 | 9", String.valueOf(res));
	}

	@Test
	public void testIteratorProtectorAndMonkeyPatching() {
		Assertions.assertTrue(BuiltinProtector.isIteratorValid(), "IteratorProtector should be initially valid");

		JSContext cx = new JSContext();
		Object res1 = cx.eval("""
			var arr = ["a", "b", "c"];
			var out = "";
			for (var item of arr) {
				out += item;
			}
			out;
		""");
		Assertions.assertEquals("abc", res1);

		// 篡改 Array.prototype[Symbol.iterator]
		cx.eval("""
			Array.prototype[Symbol.iterator] = function() {
				var idx = 0;
				return {
					next: function() {
						if (idx < 2) {
							idx++;
							return { value: "hacked", done: false };
						}
						return { value: undefined, done: true };
					}
				};
			};
		""");

		Assertions.assertFalse(BuiltinProtector.isIteratorValid(), "IteratorProtector must invalidate after modifying Array.prototype[Symbol.iterator]");

		// 再次遍历，应当走被篡改的迭代器
		Object res2 = cx.eval("""
			var arr2 = [1, 2, 3];
			var out2 = "";
			for (var item of arr2) {
				out2 += item + ",";
			}
			out2;
		""");
		Assertions.assertEquals("hacked,hacked,", res2);
	}

	@Test
	public void testGlobalSlotProtectorAndReassignment() {
		Assertions.assertTrue(BuiltinProtector.isGlobalSlotValid(JSContext.SLOT_MATH));

		JSContext cx = new JSContext();
		Object res1 = cx.eval("""
			Math.max(10, 20);
		""");
		Assertions.assertEquals(20.0, ((Number) res1).doubleValue());

		// 篡改全局 Math
		cx.eval("""
			Math = {
				max: function(a, b) {
					return 8888;
				}
			};
		""");

		Assertions.assertFalse(BuiltinProtector.isGlobalSlotValid(JSContext.SLOT_MATH), "SLOT_MATH protector must be invalidated after reassigning Math");

		Object res2 = cx.eval("""
			Math.max(10, 20);
		""");
		Assertions.assertEquals(8888.0, ((Number) res2).doubleValue());
	}
}
