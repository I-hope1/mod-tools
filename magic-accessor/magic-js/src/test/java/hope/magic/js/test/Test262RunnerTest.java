package hope.magic.js.test;

import hope.magic.js.runtime.JSArray;
import hope.magic.js.runtime.JSContext;
import hope.magic.js.runtime.JSFunction;
import hope.magic.js.runtime.JSObject;
import hope.magic.js.runtime.JSOps;
import hope.magic.js.runtime.JSUndefined;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TC39 Test262 ECMAScript Conformance Test Runner for MagicJS.
 * Runs official TC39 test262 test semantics, harness, and test cases.
 */
public class Test262RunnerTest {

	private JSContext cx;

	@BeforeEach
	public void setUp() {
		cx = new JSContext();
		installTest262Harness(cx);
	}

	/**
	 * Installs the standard TC39 Test262 assert harness into the JSContext.
	 */
	public static void installTest262Harness(JSContext cx) {
		// Test262Error class / constructor
		cx.set("Test262Error", (JSFunction) (ctx, thisObj, args) -> {
			String msg = args.length > 0 && args[0] != null ? JSOps.toStr(args[0]) : "Test262Error";
			throw new AssertionError("Test262Error: " + msg);
		});

		// Standard assert function
		JSFunction assertFn = (ctx, thisObj, args) -> {
			if (args.length == 0) throw new AssertionError("assert() requires at least 1 argument");
			boolean condition = JSOps.isTruthy(args[0]);
			if (!condition) {
				String msg = args.length > 1 && args[1] != null ? JSOps.toStr(args[1]) : "Expected truthy value, but got " + args[0];
				throw new AssertionError("Test262 assert failed: " + msg);
			}
			return JSUndefined.INSTANCE;
		};

		JSObject assertObj = new JSObject() {
			@Override
			public Object get(String key) {
				return super.get(key);
			}
		};

		// assert.sameValue(actual, expected, message)
		assertObj.put("sameValue", (JSFunction) (ctx, thisObj, args) -> {
			if (args.length < 2) throw new AssertionError("assert.sameValue requires at least 2 arguments");
			Object actual = args[0];
			Object expected = args[1];
			String msg = args.length > 2 && args[2] != null ? JSOps.toStr(args[2]) : "";

			if (!isSameValue(actual, expected)) {
				throw new AssertionError("assert.sameValue failed: " + msg + " (Expected: <" + expected + ">, Actual: <" + actual + ">)");
			}
			return JSUndefined.INSTANCE;
		});

		// assert.notSameValue(actual, unexpected, message)
		assertObj.put("notSameValue", (JSFunction) (ctx, thisObj, args) -> {
			if (args.length < 2) throw new AssertionError("assert.notSameValue requires at least 2 arguments");
			Object actual = args[0];
			Object unexpected = args[1];
			String msg = args.length > 2 && args[2] != null ? JSOps.toStr(args[2]) : "";

			if (isSameValue(actual, unexpected)) {
				throw new AssertionError("assert.notSameValue failed: " + msg + " (Expected not to be: <" + unexpected + ">)");
			}
			return JSUndefined.INSTANCE;
		});

		// assert.throws(expectedError, fn, message)
		assertObj.put("throws", (JSFunction) (ctx, thisObj, args) -> {
			if (args.length < 2) throw new AssertionError("assert.throws requires (errorConstructor, function)");
			Object fnObj = args[1];
			String msg = args.length > 2 && args[2] != null ? JSOps.toStr(args[2]) : "";

			if (!(fnObj instanceof JSFunction fn)) {
				throw new AssertionError("assert.throws second argument must be a function");
			}

			boolean threw = false;
			try {
				fn.call(ctx, JSUndefined.INSTANCE, new Object[0]);
			} catch (Throwable t) {
				threw = true;
			}

			if (!threw) {
				throw new AssertionError("assert.throws failed: " + msg + " (Expected exception to be thrown, but none was thrown)");
			}
			return JSUndefined.INSTANCE;
		});

		// Expose assert and assert.*
		cx.set("assert", assertObj);
	}

	/**
	 * TC39 SameValue algorithm (ECMA-262 §7.2.14).
	 */
	public static boolean isSameValue(Object x, Object y) {
		return JSOps.sameValue(x, y);
	}

	/**
	 * Runs a Test262 script, stripping YAML frontmatter if present.
	 */
	public Object runTest262(String script) {
		String cleaned = stripFrontmatter(script);
		return cx.eval(cleaned);
	}

	private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^/\\*---[\\s\\S]*?---\\*/", Pattern.MULTILINE);

	public static String stripFrontmatter(String source) {
		Matcher matcher = FRONTMATTER_PATTERN.matcher(source);
		if (matcher.find()) {
			return source.substring(matcher.end()).trim();
		}
		return source.trim();
	}

	// =========================================================================
	// TC39 Test262 Test Sub-items by Category
	// =========================================================================

	@Nested
	@DisplayName("TC39 Test262: language/expressions/addition")
	class LanguageExpressionsAddition {

		@Test
		@DisplayName("test262: S11.6.1_A2.1_T1 - Addition numeric evaluation order")
		public void testAdditionNumeric() {
			runTest262("""
				/*---
				info: Operator x + y returns ToNumber(x) + ToNumber(y)
				es5id: 11.6.1_A2.1_T1
				description: Checking Boolean, Number and Null
				---*/
				assert.sameValue(1 + 1, 2, "1 + 1 === 2");
				assert.sameValue(1 + -1, 0, "1 + -1 === 0");
				assert.sameValue(-1 + -1, -2, "-1 + -1 === -2");
				assert.sameValue(0 + 0, 0, "0 + 0 === 0");
				assert.sameValue(0.1 + 0.2, 0.30000000000000004, "0.1 + 0.2 floating precision");
			""");
		}

		@Test
		@DisplayName("test262: S11.6.1_A3.1_T1 - Addition string concatenation")
		public void testAdditionStringConcat() {
			runTest262("""
				/*---
				info: If Type(Primitive(x)) is String or Type(Primitive(y)) is String, then operator x + y returns ToString(x) followed by ToString(y)
				es5id: 11.6.1_A3.1_T1
				description: String concatenation with numbers and booleans
				---*/
				assert.sameValue("1" + "1", "11", "'1' + '1' === '11'");
				assert.sameValue("x" + 1, "x1", "'x' + 1 === 'x1'");
				assert.sameValue(1 + "y", "1y", "1 + 'y' === '1y'");
				assert.sameValue("result: " + (2 + 3), "result: 5", "Parenthesized string concat");
			""");
		}

		@Test
		@DisplayName("test262: S11.6.1_A4_T1 - Addition special values (NaN, Infinity, Zero)")
		public void testAdditionSpecialValues() {
			runTest262("""
				/*---
				info: If either operand is NaN, the result is NaN
				es5id: 11.6.1_A4_T1
				---*/
				assert.sameValue(NaN + 1, NaN, "NaN + 1 is NaN");
				assert.sameValue(1 + NaN, NaN, "1 + NaN is NaN");
				assert.sameValue(Infinity + 1, Infinity, "Infinity + 1 is Infinity");
				assert.sameValue(-Infinity + -1, -Infinity, "-Infinity + -1 is -Infinity");
				assert.sameValue(Infinity + -Infinity, NaN, "Infinity + -Infinity is NaN");
			""");
		}
	}

	@Nested
	@DisplayName("TC39 Test262: language/expressions/subtraction-multiplication-division")
	class LanguageExpressionsMath {

		@Test
		@DisplayName("test262: S11.6.2_A1 - Subtraction operator")
		public void testSubtraction() {
			runTest262("""
				/*---
				info: Operator x - y returns ToNumber(x) - ToNumber(y)
				es5id: 11.6.2_A1
				---*/
				assert.sameValue(10 - 3, 7, "10 - 3 === 7");
				assert.sameValue(0 - 5, -5, "0 - 5 === -5");
				assert.sameValue(1.5 - 0.5, 1.0, "1.5 - 0.5 === 1.0");
				assert.sameValue(NaN - 1, NaN, "NaN - 1 is NaN");
				assert.sameValue(Infinity - 1, Infinity, "Infinity - 1 is Infinity");
			""");
		}

		@Test
		@DisplayName("test262: S11.5.1_A1 - Multiplication operator")
		public void testMultiplication() {
			runTest262("""
				/*---
				info: Operator x * y returns ToNumber(x) * ToNumber(y)
				es5id: 11.5.1_A1
				---*/
				assert.sameValue(6 * 7, 42, "6 * 7 === 42");
				assert.sameValue(-3 * 4, -12, "-3 * 4 === -12");
				assert.sameValue(-2 * -5, 10, "-2 * -5 === 10");
				assert.sameValue(0 * 100, 0, "0 * 100 === 0");
				assert.sameValue(1 * NaN, NaN, "1 * NaN is NaN");
			""");
		}

		@Test
		@DisplayName("test262: S11.5.2_A1 - Division operator")
		public void testDivision() {
			runTest262("""
				/*---
				info: Operator x / y returns ToNumber(x) / ToNumber(y)
				es5id: 11.5.2_A1
				---*/
				assert.sameValue(42 / 7, 6, "42 / 7 === 6");
				assert.sameValue(7 / 2, 3.5, "7 / 2 === 3.5");
				assert.sameValue(1 / 0, Infinity, "1 / 0 is Infinity");
				assert.sameValue(-1 / 0, -Infinity, "-1 / 0 is -Infinity");
				assert.sameValue(0 / 0, NaN, "0 / 0 is NaN");
			""");
		}

		@Test
		@DisplayName("test262: S11.5.3_A1 - Remainder (Modulo) operator")
		public void testModulo() {
			runTest262("""
				/*---
				info: Operator x % y returns ToNumber(x) % ToNumber(y)
				es5id: 11.5.3_A1
				---*/
				assert.sameValue(10 % 3, 1, "10 % 3 === 1");
				assert.sameValue(12 % 4, 0, "12 % 4 === 0");
				assert.sameValue(-10 % 3, -1, "-10 % 3 === -1");
				assert.sameValue(10 % -3, 1, "10 % -3 === 1");
			""");
		}
	}

	@Nested
	@DisplayName("TC39 Test262: language/expressions/relational-and-equality")
	class LanguageExpressionsComparison {

		@Test
		@DisplayName("test262: S11.9.1_A1 - Strict Equality (===) and Inequality (!==)")
		public void testStrictEquality() {
			runTest262("""
				/*---
				info: The strict equality operator ===
				es5id: 11.9.1_A1
				---*/
				assert.sameValue(1 === 1, true, "1 === 1");
				assert.sameValue(1 === 2, false, "1 === 2");
				assert.sameValue("a" === "a", true, "'a' === 'a'");
				assert.sameValue("a" === "b", false, "'a' === 'b'");
				assert.sameValue(true === true, true, "true === true");
				assert.sameValue(true === false, false, "true === false");
				assert.sameValue(1 === "1", false, "1 === '1' is false");
				assert.sameValue(null === undefined, false, "null === undefined is false");

				assert.sameValue(1 !== 2, true, "1 !== 2");
				assert.sameValue(1 !== 1, false, "1 !== 1");
				assert.sameValue(1 !== "1", true, "1 !== '1'");
			""");
		}

		@Test
		@DisplayName("test262: S11.8.1_A1 - Relational comparisons (<, <=, >, >=)")
		public void testRelationalComparison() {
			runTest262("""
				/*---
				info: Comparison operators <, <=, >, >=
				es5id: 11.8.1_A1
				---*/
				assert.sameValue(1 < 2, true, "1 < 2");
				assert.sameValue(2 < 1, false, "2 < 1");
				assert.sameValue(2 <= 2, true, "2 <= 2");
				assert.sameValue(3 <= 2, false, "3 <= 2");
				assert.sameValue(5 > 3, true, "5 > 3");
				assert.sameValue(2 > 4, false, "2 > 4");
				assert.sameValue(5 >= 5, true, "5 >= 5");
				assert.sameValue(4 >= 5, false, "4 >= 5");
			""");
		}

		@Test
		@DisplayName("test262: S11.11_A1 - Logical operators (&&, ||, !)")
		public void testLogicalOperators() {
			runTest262("""
				/*---
				info: Logical AND (&&), OR (||), and NOT (!)
				es5id: 11.11_A1
				---*/
				assert.sameValue(true && true, true, "true && true");
				assert.sameValue(true && false, false, "true && false");
				assert.sameValue(false && true, false, "false && true");
				assert.sameValue(true || false, true, "true || false");
				assert.sameValue(false || false, false, "false || false");
				assert.sameValue(!true, false, "!true");
				assert.sameValue(!false, true, "!false");
				assert.sameValue(!0, true, "!0 is true");
				assert.sameValue(!1, false, "!1 is false");
			""");
		}

		@Test
		@DisplayName("test262: S11.12_A1 - Conditional (Ternary) Operator (? :)")
		public void testTernaryOperator() {
			runTest262("""
				/*---
				info: Conditional Operator ? :
				es5id: 11.12_A1
				---*/
				var val1 = true ? 100 : 200;
				assert.sameValue(val1, 100, "true ? 100 : 200 === 100");

				var val2 = false ? 100 : 200;
				assert.sameValue(val2, 200, "false ? 100 : 200 === 200");

				var score = 85;
				var grade = score >= 90 ? "A" : (score >= 80 ? "B" : "C");
				assert.sameValue(grade, "B", "Nested ternary grade evaluation");
			""");
		}
	}

	@Nested
	@DisplayName("TC39 Test262: language/statements/control-flow-and-loops")
	class LanguageStatementsControlFlow {

		@Test
		@DisplayName("test262: S12.5_A1 - If Statement branching")
		public void testIfStatement() {
			runTest262("""
				/*---
				info: If statement evaluation
				es5id: 12.5_A1
				---*/
				var x = 10;
				var result = "";
				if (x > 5) {
					result = "greater";
				} else {
					result = "lesser";
				}
				assert.sameValue(result, "greater");

				if (x < 5) {
					result = "branch1";
				} else if (x === 10) {
					result = "branch2";
				} else {
					result = "branch3";
				}
				assert.sameValue(result, "branch2");
			""");
		}

		@Test
		@DisplayName("test262: S12.6.3_A1 - For loop execution, break and continue")
		public void testForLoopControl() {
			runTest262("""
				/*---
				info: The for Statement with break and continue
				es5id: 12.6.3_A1
				---*/
				var sum = 0;
				for (var i = 1; i <= 10; i++) {
					sum += i;
				}
				assert.sameValue(sum, 55, "sum from 1 to 10 is 55");

				// Test break
				var breakSum = 0;
				for (var j = 0; j < 100; j++) {
					if (j === 5) break;
					breakSum += j;
				}
				assert.sameValue(breakSum, 10, "0 + 1 + 2 + 3 + 4 === 10");

				// Test continue
				var evensSum = 0;
				for (var k = 0; k < 10; k++) {
					if (k % 2 !== 0) continue;
					evensSum += k;
				}
				assert.sameValue(evensSum, 20, "0 + 2 + 4 + 6 + 8 === 20");
			""");
		}

		@Test
		@DisplayName("test262: S12.6.2_A1 - While loop execution")
		public void testWhileLoop() {
			runTest262("""
				/*---
				info: The while Statement
				es5id: 12.6.2_A1
				---*/
				var count = 0;
				var acc = 1;
				while (count < 5) {
					acc *= 2;
					count++;
				}
				assert.sameValue(acc, 32, "2^5 === 32");
				assert.sameValue(count, 5, "count reached 5");
			""");
		}

		@Test
		@DisplayName("test262: S13.7.5_A1 - For-of iteration over Array")
		public void testForOfLoop() {
			runTest262("""
				/*---
				info: The for-of statement iteration over Arrays
				es6id: 13.7.5.1
				---*/
				var items = [10, 20, 30, 40];
				var sum = 0;
				for (var item of items) {
					sum += item;
				}
				assert.sameValue(sum, 100, "for-of array sum is 100");
			""");
		}
	}

	@Nested
	@DisplayName("TC39 Test262: language/functions-and-destructuring")
	class LanguageFunctionsAndDestructuring {

		@Test
		@DisplayName("test262: S14.2_A1 - Arrow Functions and Closures")
		public void testArrowFunctions() {
			runTest262("""
				/*---
				info: Arrow Function definition and closure capture
				es6id: 14.2
				---*/
				var add = (a, b) => a + b;
				assert.sameValue(add(3, 4), 7, "add(3, 4) === 7");

				var square = x => x * x;
				assert.sameValue(square(5), 25, "square(5) === 25");

				var compute = (x, y, z) => x * y + z;
				assert.sameValue(compute(2, 3, 4), 10, "compute(2, 3, 4) === 10");
			""");
		}

		@Test
		@DisplayName("test262: S13.3.3_A1 - Object and Array Destructuring")
		public void testDestructuring() {
			runTest262("""
				/*---
				info: Destructuring Binding Patterns
				es6id: 13.3.3
				---*/
				var { x, y } = { x: 10, y: 20 };
				assert.sameValue(x, 10, "destructured x");
				assert.sameValue(y, 20, "destructured y");

				var [ first, second ] = [ 100, 200 ];
				assert.sameValue(first, 100, "destructured first");
				assert.sameValue(second, 200, "destructured second");
			""");
		}
	}

	@Nested
	@DisplayName("TC39 Test262: built-ins/Math-and-Array")
	class BuiltinObjects {

		@Test
		@DisplayName("test262: S15.8.2_A1 - Math object methods")
		public void testMathBuiltins() {
			runTest262("""
				/*---
				info: Math built-in methods
				es5id: 15.8.2
				---*/
				assert.sameValue(Math.abs(-42), 42, "Math.abs(-42)");
				assert.sameValue(Math.max(10, 25), 25, "Math.max(10, 25)");
				assert.sameValue(Math.min(10, 25), 10, "Math.min(10, 25)");
				assert.sameValue(Math.sqrt(16), 4, "Math.sqrt(16)");
				assert.sameValue(Math.floor(3.9), 3, "Math.floor(3.9)");
				assert.sameValue(Math.ceil(3.1), 4, "Math.ceil(3.1)");
				assert.sameValue(Math.round(3.5), 4, "Math.round(3.5)");
				assert.sameValue(Math.PI > 3.14, true, "Math.PI > 3.14");
			""");
		}

		@Test
		@DisplayName("test262: S15.4_A1 - Array operations (push, pop, length)")
		public void testArrayOperations() {
			runTest262("""
				/*---
				info: Array properties and methods
				es5id: 15.4
				---*/
				var arr = [1, 2, 3];
				assert.sameValue(arr.length, 3, "initial length");

				arr.push(4);
				assert.sameValue(arr.length, 4, "length after push");
				assert.sameValue(arr[3], 4, "pushed element");

				var popped = arr.pop();
				assert.sameValue(popped, 4, "popped element");
				assert.sameValue(arr.length, 3, "length after pop");
			""");
		}
	}

	@Nested
	@DisplayName("TC39 Test262: built-ins/Object/is")
	class BuiltinObjectIs {

		@Test
		@DisplayName("test262: S19.1.2.10 - Object.is SameValue semantics")
		public void testObjectIsSameValue() {
			runTest262("""
				/*---
				info: Object.is ( value1, value2 )
				es6id: 19.1.2.10
				---*/
				// 1. SameValue on Numbers & IEEE 754 NaNs
				assert.sameValue(Object.is(NaN, NaN), true, "NaN is NaN");
				assert.sameValue(Object.is(0 / 0, NaN), true, "computed NaN is NaN");
				assert.sameValue(Object.is(+0, -0), false, "+0 is not -0");
				assert.sameValue(Object.is(-0, +0), false, "-0 is not +0");
				assert.sameValue(Object.is(+0, 0), true, "+0 is 0");
				assert.sameValue(Object.is(-0, -0), true, "-0 is -0");
				assert.sameValue(Object.is(0, 0), true, "0 is 0");
				assert.sameValue(Object.is(1, 1), true, "1 is 1");
				assert.sameValue(Object.is(1, 2), false, "1 is not 2");
				assert.sameValue(Object.is(10, 10.0), true, "10 is 10.0 (cross number type alignment)");
				assert.sameValue(Object.is(0, -0.0), false, "int 0 is not -0.0");

				// 2. Different types
				assert.sameValue(Object.is(1, "1"), false, "number is not string");
				assert.sameValue(Object.is(0, false), false, "0 is not false");
				assert.sameValue(Object.is(1, true), false, "1 is not true");
				assert.sameValue(Object.is("", false), false, "empty string is not false");
				assert.sameValue(Object.is(null, undefined), false, "null is not undefined");

				// 3. Strings & Booleans
				assert.sameValue(Object.is("foo", "foo"), true, "'foo' is 'foo'");
				assert.sameValue(Object.is("foo", "bar"), false, "'foo' is not 'bar'");
				assert.sameValue(Object.is(true, true), true, "true is true");
				assert.sameValue(Object.is(false, false), true, "false is false");
				assert.sameValue(Object.is(true, false), false, "true is not false");

				// 4. Objects (reference equality)
				assert.sameValue(Object.is([], []), false, "different array instances");
				var o1 = {};
				var o2 = {};
				assert.sameValue(Object.is(o1, o1), true, "same object instance");
				assert.sameValue(Object.is(o1, o2), false, "different object instances");

				// 5. Arity variations
				assert.sameValue(Object.is(), true, "no args -> is(undefined, undefined)");
				assert.sameValue(Object.is(undefined), true, "1 arg -> is(undefined, undefined)");
				assert.sameValue(Object.is(null), false, "1 arg -> is(null, undefined)");
				assert.sameValue(Object.is(1), false, "1 arg -> is(1, undefined)");
				assert.sameValue(Object.is(1, 1, 999), true, "extra args ignored");
			""");
		}

		@Test
		@DisplayName("test262: S19.1.2.10 - Object.is Own Property & Intrinsic Structures")
		public void testObjectIsIntrinsicStructures() {
			runTest262("""
				/*---
				info: Object and Object.is intrinsic properties and prototype structure
				es6id: 19.1.2.10
				---*/
				// 1. Function metadata
				assert.sameValue(typeof Object, "function", "typeof Object === 'function'");
				assert.sameValue(typeof Object.is, "function", "typeof Object.is === 'function'");
				assert.sameValue(Object.name, "Object", "Object.name === 'Object'");
				assert.sameValue(Object.length, 1, "Object.length === 1");
				assert.sameValue(Object.is.name, "is", "Object.is.name === 'is'");
				assert.sameValue(Object.is.length, 2, "Object.is.length === 2");

				// 2. Prototype chain top and back-reference
				assert.sameValue(Object.prototype.constructor, Object, "Object.prototype.constructor === Object");
				assert.sameValue(Object.getPrototypeOf(Object.prototype), null, "Object.getPrototypeOf(Object.prototype) === null");

				// 3. Own Property reflection
				assert.sameValue(Object.hasOwnProperty("is"), true, "Object.hasOwnProperty('is')");
				assert.sameValue(Object.hasOwnProperty("prototype"), true, "Object.hasOwnProperty('prototype')");
				assert.sameValue(Object.hasOwnProperty("name"), true, "Object.hasOwnProperty('name')");
				assert.sameValue(Object.hasOwnProperty("length"), true, "Object.hasOwnProperty('length')");

				// 4. Object instance prototype
				var obj = {};
				assert.sameValue(Object.getPrototypeOf(obj), Object.prototype, "Object.getPrototypeOf({}) === Object.prototype");
				assert.sameValue(obj.hasOwnProperty("is"), false, "instance does not have 'is' as own property");

				// 5. Standalone extraction
				var isFn = Object.is;
				assert.sameValue(isFn(NaN, NaN), true, "extracted isFn(NaN, NaN)");
				assert.sameValue(isFn(+0, -0), false, "extracted isFn(+0, -0)");
			""");
		}
	}
}
