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
		JSContext.realmCreatedListener = Test262RunnerTest::installTest262Harness;

		// Test262Error class / constructor
		cx.set("Test262Error", (JSFunction) (ctx, thisObj, args) -> {
			String msg = args.length > 0 && args[0] != null ? JSOps.toStr(args[0]) : "Test262Error";
			throw new AssertionError("Test262Error: " + msg);
		});

		cx.set("isSameValue", (JSFunction) (ctx, thisObj, args) -> isSameValue(
				args.length > 0 ? args[0] : JSUndefined.INSTANCE,
				args.length > 1 ? args[1] : JSUndefined.INSTANCE
		));

		AssertFunction assertObj = new AssertFunction();

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

		cx.eval("""
			var __isArray = Array.isArray;
			var __defineProperty = Object.defineProperty;
			var __getOwnPropertyDescriptor = Object.getOwnPropertyDescriptor;
			var __getOwnPropertyNames = Object.getOwnPropertyNames;
			var __join = Function.prototype.call.bind(Array.prototype.join);
			var __push = Function.prototype.call.bind(Array.prototype.push);
			var __hasOwnProperty = Function.prototype.call.bind(Object.prototype.hasOwnProperty);
			var __propertyIsEnumerable = Function.prototype.call.bind(Object.prototype.propertyIsEnumerable);
			var nonIndexNumericPropertyName = Math.pow(2, 32) - 1;

			function verifyProperty(obj, name, desc, options) {
			  assert(
			    arguments.length > 2,
			    'verifyProperty should receive at least 3 arguments: obj, name, and descriptor'
			  );
			  var label = options && options.label || String(name);
			  var originalDesc = __getOwnPropertyDescriptor(obj, name);
			  if (desc === undefined) {
			    assert.sameValue(
			      originalDesc,
			      undefined,
			      label + " descriptor should be undefined"
			    );
			    return true;
			  }
			  assert(__hasOwnProperty(obj, name), label + " should be an own property");
			  assert.notSameValue(
			    desc,
			    null,
			    "The desc argument should be an object or undefined, null"
			  );
			  assert.sameValue(
			    typeof desc,
			    "object",
			    "The desc argument should be an object or undefined, " + String(desc)
			  );
			  var names = __getOwnPropertyNames(desc);
			  for (var i = 0; i < names.length; i++) {
			    assert(
			      names[i] === "value" ||
			        names[i] === "writable" ||
			        names[i] === "enumerable" ||
			        names[i] === "configurable" ||
			        names[i] === "get" ||
			        names[i] === "set",
			      "Invalid descriptor field: " + names[i]
			    );
			  }
			  var failures = [];
			  if (__hasOwnProperty(desc, 'value')) {
			    if (!isSameValue(desc.value, originalDesc.value)) {
			      __push(failures, label + " descriptor value should be " + String(desc.value));
			    }
			    if (!isSameValue(desc.value, obj[name])) {
			      __push(failures, label + " value should be " + String(desc.value));
			    }
			  }
			  if (__hasOwnProperty(desc, 'enumerable') && desc.enumerable !== undefined) {
			    if (desc.enumerable !== originalDesc.enumerable ||
			        desc.enumerable !== isEnumerable(obj, name)) {
			      __push(failures, label + " descriptor should " + (desc.enumerable ? '' : 'not ') + "be enumerable");
			    }
			  }
			  if (__hasOwnProperty(desc, 'writable') && desc.writable !== undefined) {
			    if (desc.writable !== originalDesc.writable ||
			        desc.writable !== isWritable(obj, name)) {
			      __push(failures, label + " descriptor should " + (desc.writable ? '' : 'not ') + "be writable");
			    }
			  }
			  if (__hasOwnProperty(desc, 'configurable') && desc.configurable !== undefined) {
			    if (desc.configurable !== originalDesc.configurable ||
			        desc.configurable !== isConfigurable(obj, name)) {
			      __push(failures, label + " descriptor should " + (desc.configurable ? '' : 'not ') + "be configurable");
			    }
			  }
			  if (failures.length) {
			    assert(false, __join(failures, '; '));
			  }
			  if (options && options.restore) {
			    __defineProperty(obj, name, originalDesc);
			  }
			  return true;
			}

			function isConfigurable(obj, name) {
			  try {
			    delete obj[name];
			  } catch (e) {
			    if (!(e instanceof TypeError)) {
			      throw new Test262Error("Expected TypeError, got " + e);
			    }
			  }
			  return !__hasOwnProperty(obj, name);
			}

			function isEnumerable(obj, name) {
			  var stringCheck = false;
			  if (typeof name === "string") {
			    for (var x in obj) {
			      if (x === name) {
			        stringCheck = true;
			        break;
			      }
			    }
			  } else {
			    stringCheck = true;
			  }
			  return stringCheck && __hasOwnProperty(obj, name) && __propertyIsEnumerable(obj, name);
			}

			function isWritable(obj, name, verifyProp, value) {
			  var unlikelyValue = __isArray(obj) && name === "length" ?
			    nonIndexNumericPropertyName :
			    "unlikelyValue";
			  var newValue = value || unlikelyValue;
			  var hadValue = __hasOwnProperty(obj, name);
			  var oldValue = obj[name];
			  var writeSucceeded;
			  if (arguments.length < 4 && newValue === oldValue) {
			    newValue = newValue + "2";
			  }
			  try {
			    obj[name] = newValue;
			  } catch (e) {
			    if (!(e instanceof TypeError)) {
			      throw new Test262Error("Expected TypeError, got " + e);
			    }
			  }
			  writeSucceeded = isSameValue(obj[verifyProp || name], newValue);
			  if (writeSucceeded) {
			    if (hadValue) {
			      obj[name] = oldValue;
			    } else {
			      delete obj[name];
			    }
			  }
			  return writeSucceeded;
			}
		""");
	}

	public static class AssertFunction extends JSObject implements JSFunction {
		@Override
		public Object call(JSContext ctx, Object thisObj, Object[] args) {
			if (args.length == 0) throw new AssertionError("assert() requires at least 1 argument");
			boolean condition = JSOps.isTruthy(args[0]);
			if (!condition) {
				String msg = args.length > 1 && args[1] != null ? JSOps.toStr(args[1]) : "Expected truthy value, but got " + args[0];
				throw new AssertionError("Test262 assert failed: " + msg);
			}
			return JSUndefined.INSTANCE;
		}
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

	@Nested
	@DisplayName("TC39 Test262: Array & Array.prototype Methods")
	class ArrayPrototypeMethodsTest {

		@Test
		@DisplayName("test262: S22.1.1 & S22.1.2.2 - Array Constructor, RangeError and Array.isArray")
		public void testArrayConstructorAndIsArray() {
			runTest262("""
				/*---
				info: Array constructor, RangeError validation, and Array.isArray
				es6id: 22.1.1, 22.1.2.2
				---*/
				// 1. Array Constructor Metadata
				assert.sameValue(typeof Array, "function", "typeof Array === 'function'");
				assert.sameValue(Array.name, "Array", "Array.name === 'Array'");
				assert.sameValue(Array.length, 1, "Array.length === 1");
				assert.sameValue(Array.prototype.constructor, Array, "Array.prototype.constructor === Array");
				assert.sameValue(Object.getPrototypeOf(Array.prototype), Object.prototype, "Object.getPrototypeOf(Array.prototype) === Object.prototype");
				assert.sameValue(Object.getPrototypeOf([]), Array.prototype, "Object.getPrototypeOf([]) === Array.prototype");

				// 2. Array.isArray
				assert.sameValue(Array.isArray([]), true, "Array.isArray([]) === true");
				assert.sameValue(Array.isArray(new Array(3)), true, "Array.isArray(new Array(3)) === true");
				assert.sameValue(Array.isArray({ length: 0 }), false, "Array.isArray({ length: 0 }) === false");
				assert.sameValue(Array.isArray("array"), false, "Array.isArray('array') === false");
				assert.sameValue(Array.isArray(null), false, "Array.isArray(null) === false");
				assert.sameValue(Array.isArray(undefined), false, "Array.isArray(undefined) === false");
				assert.sameValue(Array.isArray(123), false, "Array.isArray(123) === false");

				// 3. Array.of & Array.from
				var ofArr = Array.of(1, "two", 3);
				assert.sameValue(ofArr.length, 3, "Array.of length");
				assert.sameValue(ofArr[0], 1, "Array.of[0]");
				assert.sameValue(ofArr[1], "two", "Array.of[1]");

				var fromArr = Array.from({ length: 2, 0: "x", 1: "y" });
				assert.sameValue(fromArr.length, 2, "Array.from length");
				assert.sameValue(fromArr[0], "x", "Array.from[0]");
				assert.sameValue(fromArr[1], "y", "Array.from[1]");

				// 4. new Array() argument variations
				var emptyArr = new Array();
				assert.sameValue(emptyArr.length, 0, "new Array() empty length");

				var strArr = new Array("5");
				assert.sameValue(strArr.length, 1, "new Array('5') length");
				assert.sameValue(strArr[0], "5", "new Array('5')[0]");

				var multiArr = new Array(10, 20, 30);
				assert.sameValue(multiArr.length, 3, "new Array(10, 20, 30) length");
				assert.sameValue(multiArr[1], 20, "new Array(10, 20, 30)[1]");

				// 5. RangeError Boundary Defense
				assert.throws(RangeError, function() { new Array(-1); }, "new Array(-1) throws RangeError");
				assert.throws(RangeError, function() { new Array(3.14); }, "new Array(3.14) throws RangeError");
				assert.throws(RangeError, function() { new Array(NaN); }, "new Array(NaN) throws RangeError");
				assert.throws(RangeError, function() { new Array(4294967296); }, "new Array(2^32) throws RangeError");
				assert.throws(RangeError, function() { Array(-5); }, "Array(-5) throws RangeError");
				assert.throws(RangeError, function() { Array(1.5); }, "Array(1.5) throws RangeError");
			""");
		}

		@Test
		@DisplayName("test262: S15.4.1_A2.2_T1 - Array(0..99) 100 arguments and hoisted var in loop")
		public void testArrayConstructor100ArgsAndHoistedVarInLoop() {
			runTest262("""
				/*---
				info: Array constructor with 100 arguments and hoisted var in loop
				es5id: 15.4.1_A2.2_T1
				---*/
				var x = Array(
				  0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
				  10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
				  20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
				  30, 31, 32, 33, 34, 35, 36, 37, 38, 39,
				  40, 41, 42, 43, 44, 45, 46, 47, 48, 49,
				  50, 51, 52, 53, 54, 55, 56, 57, 58, 59,
				  60, 61, 62, 63, 64, 65, 66, 67, 68, 69,
				  70, 71, 72, 73, 74, 75, 76, 77, 78, 79,
				  80, 81, 82, 83, 84, 85, 86, 87, 88, 89,
				  90, 91, 92, 93, 94, 95, 96, 97, 98, 99
				);

				for (var i = 0; i < 100; i++) {
				  var result = true;
				  if (x[i] !== i) {
				    result = false;
				  }
				}

				assert.sameValue(result, true, 'The value of result is expected to be true');
			""");
		}

		@Test
		@DisplayName("test262: S15.4.5.1_A1.2_T2 - Array length truncation and inherited prototype property")
		public void testArrayPrototypeInheritedPropertyTruncationAndDeletion() {
			runTest262("""
				/*---
				info: |
				    For every integer k that is less than the value of
				    the length property of A but not less than ToUint32(length),
				    if A itself has a property (not an inherited property) named ToString(k),
				    then delete that property
				es5id: 15.4.5.1_A1.2_T2
				description: Checking an inherited property
				---*/

				Array.prototype[2] = -1;
				var x = [0, 1, 2];
				assert.sameValue(x[2], 2, 'The value of x[2] is expected to be 2');

				x.length = 2;
				assert.sameValue(x[2], -1, 'The value of x[2] is expected to be -1');

				delete Array.prototype[2];
			""");
		}

		@Test
		@DisplayName("test262: S15.4.5.2_A3_T2 - Array length set with Number object wrapper and automatic deletion")
		public void testArrayLengthSetNumberObject() {
			runTest262("""
				/*---
				info: |
				    If the length property is changed, every property whose name
				    is an array index whose value is not smaller than the new length is automatically deleted
				es5id: 15.4.5.2_A3_T2
				description: >
				    If new length greater than the name of every property whose name
				    is an array index
				---*/

				var x = [];
				x[1] = 1;
				x[3] = 3;
				x[5] = 5;
				x.length = 4;
				assert.sameValue(x.length, 4, 'The value of x.length is expected to be 4');
				assert.sameValue(x[5], undefined, 'The value of x[5] is expected to equal undefined');
				assert.sameValue(x[3], 3, 'The value of x[3] is expected to be 3');

				x.length = new Number(6);
				assert.sameValue(x[5], undefined, 'The value of x[5] is expected to equal undefined');

				x.length = 0;
				assert.sameValue(x[0], undefined, 'The value of x[0] is expected to equal undefined');

				x.length = 1;
				assert.sameValue(x[1], undefined, 'The value of x[1] is expected to equal undefined');
			""");
		}

		@Test
		@DisplayName("test262: S15.4_A1.1_T6 - Array index vs property key with boolean primitive and Boolean object")
		public void testArrayBooleanObjectPropertyKey() {
			runTest262("""
				/*---
				info: |
				    A property name P (in the form of a string value) is an array index
				    if and only if ToString(ToUint32(P)) is equal to P and ToUint32(P) is not equal to 2^32 - 1
				es5id: 15.4_A1.1_T6
				description: Checking for boolean primitive and Boolean object
				---*/

				var x = [];

				x[true] = 1;
				assert.sameValue(x[1], undefined, 'The value of x[1] is expected to equal undefined');
				assert.sameValue(x["true"], 1, 'The value of x["true"] is expected to be 1');

				x[new Boolean(true)] = 1;
				assert.sameValue(x[1], undefined, 'The value of x[1] is expected to equal undefined');
				assert.sameValue(x["true"], 1, 'The value of x["true"] is expected to be 1');

				x[false] = 0;
				assert.sameValue(x[0], undefined, 'The value of x[0] is expected to equal undefined');
				assert.sameValue(x["false"], 0, 'The value of x["false"] is expected to be 0');

				x[new Boolean(false)] = 0;
				assert.sameValue(x[0], undefined, 'The value of x[0] is expected to equal undefined');
				assert.sameValue(x["false"], 0, 'The value of x["false"] is expected to be 0');
			""");
		}

		@Test
		@DisplayName("test262: S15.4_A1.1_T2 - Checking for number primitive (NaN, POSITIVE_INFINITY, NEGATIVE_INFINITY)")
		public void testArrayIndexNumberPrimitive() {
			runTest262("""
				/*---
				info: |
				    A property name P (in the form of a string value) is an array index
				    if and only if ToString(ToUint32(P)) is equal to P and ToUint32(P) is not equal to 2^32 - 1
				es5id: 15.4_A1.1_T2
				description: Checking for number primitive
				---*/

				var x = [];

				x[NaN] = 1;
				assert.sameValue(x[0], undefined, 'The value of x[0] is expected to equal undefined');
				assert.sameValue(x["NaN"], 1, 'The value of x["NaN"] is expected to be 1');

				var y = [];
				y[Number.POSITIVE_INFINITY] = 1;
				assert.sameValue(y[0], undefined, 'The value of y[0] is expected to equal undefined');
				assert.sameValue(y["Infinity"], 1, 'The value of y["Infinity"] is expected to be 1');

				var z = [];
				z[Number.NEGATIVE_INFINITY] = 1;
				assert.sameValue(z[0], undefined, 'The value of z[0] is expected to equal undefined');
				assert.sameValue(z["-Infinity"], 1, 'The value of z["-Infinity"] is expected to be 1');
			""");
		}

		@Test
		@DisplayName("test262: S22.1.3.18 - Array.prototype.reduce & reduceRight")
		public void testArrayPrototypeReduce() {
			runTest262("""
				/*---
				info: Array.prototype.reduce and reduceRight specification compliance
				es6id: 22.1.3.18, 22.1.3.19
				---*/
				// 1. Metadata
				assert.sameValue(typeof Array.prototype.reduce, "function", "typeof reduce === 'function'");
				assert.sameValue(Array.prototype.reduce.name, "reduce", "reduce.name === 'reduce'");
				assert.sameValue(Array.prototype.reduce.length, 1, "reduce.length === 1");

				// 2. Dense fast-path reduce
				var sum = [1, 2, 3, 4].reduce(function(acc, x) { return acc + x; }, 0);
				assert.sameValue(sum, 10, "reduce with initial value");

				var sumNoInit = [1, 2, 3, 4].reduce(function(acc, x) { return acc + x; });
				assert.sameValue(sumNoInit, 10, "reduce without initial value");

				var single = [42].reduce(function(acc, x) { return acc + x; });
				assert.sameValue(single, 42, "single element without initial value");

				// 3. Holes vs Undefined
				// [undefined] has 1 element, executes callback
				var undefCalls = [undefined].reduce(function(acc, x) { return acc + 1; }, 0);
				assert.sameValue(undefCalls, 1, "[undefined].reduce executes 1 time");

				// new Array(1) has only holes, reduce with initial value executes 0 times
				var holeCalls = new Array(1).reduce(function(acc, x) { return acc + 1; }, 0);
				assert.sameValue(holeCalls, 0, "new Array(1).reduce executes 0 times");

				// new Array(1) without initial value throws TypeError
				assert.throws(TypeError, function() {
					new Array(1).reduce(function(acc, x) { return acc + x; });
				}, "new Array(1) without initial value throws TypeError");

				// Empty array without initial value throws TypeError
				assert.throws(TypeError, function() {
					[].reduce(function(acc, x) { return acc + x; });
				}, "[].reduce() with no initial value throws TypeError");

				// 4. Holes skipping during reduction
				var sparseArr = new Array(3);
				sparseArr[1] = 10;
				var sparseSum = sparseArr.reduce(function(acc, x) { return acc + x; }, 5);
				assert.sameValue(sparseSum, 15, "reduce skips holes");

				// 5. Generic reduce on array-like object
				var arrayLike = { length: 3, 0: 10, 1: 20, 2: 30 };
				var genericSum = Array.prototype.reduce.call(arrayLike, function(a, b) { return a + b; }, 0);
				assert.sameValue(genericSum, 60, "generic reduce on array-like object");

				// 6. 4 Callback arguments passed accurately
				var validArgsCount = [100, 200, 300].reduce(function(acc, val, idx, arr) {
					return acc + (arr.length === 3 ? 1 : 0);
				}, 0);
				assert.sameValue(validArgsCount, 3, "reduce callback receives (acc, cur, idx, arr)");

				// 7. reduceRight
				var rSum = ["a", "b", "c"].reduceRight(function(acc, x) { return acc + x; }, "");
				assert.sameValue(rSum, "cba", "reduceRight accumulates right to left");

				var rSub = [1, 2, 3, 4].reduceRight(function(acc, x) { return acc - x; });
				assert.sameValue(rSub, -2, "reduceRight without initial value: 4 - 3 - 2 - 1 = -2");

				// Non-callable throws TypeError
				assert.throws(TypeError, function() {
					[1, 2].reduce(null);
				}, "non-function throws TypeError");
			""");
		}

		@Test
		@DisplayName("test262: S22.1.3.7 - Array.prototype.filter")
		public void testArrayPrototypeFilter() {
			runTest262("""
				/*---
				info: Array.prototype.filter specification compliance
				es6id: 22.1.3.7
				---*/
				// 1. Metadata
				assert.sameValue(typeof Array.prototype.filter, "function", "typeof filter === 'function'");
				assert.sameValue(Array.prototype.filter.name, "filter", "filter.name === 'filter'");
				assert.sameValue(Array.prototype.filter.length, 1, "filter.length === 1");

				// 2. Dense fast-path filter
				var evens = [1, 2, 3, 4, 5, 6].filter(function(x) { return x % 2 === 0; });
				assert.sameValue(evens.length, 3, "filter evens length");
				assert.sameValue(evens.join(","), "2,4,6", "filter evens content");

				// 3. Holes skipping
				var holey = new Array(3);
				holey[0] = 1;
				holey[2] = 3;
				var kept = holey.filter(function() { return true; });
				assert.sameValue(kept.length, 2, "filter skips holes");
				assert.sameValue(kept[0], 1, "kept[0]");
				assert.sameValue(kept[1], 3, "kept[1]");

				// 4. thisArg binding
				var context = { threshold: 3 };
				var greaterThan3 = [1, 2, 3, 4, 5].filter(function(x) {
					return x > this.threshold;
				}, context);
				assert.sameValue(greaterThan3.join(","), "4,5", "filter thisArg binding");

				// 5. Generic filter on array-like object
				var arrayLike = { length: 4, 0: "apple", 1: "banana", 2: "apricot", 3: "cherry" };
				var startsWithA = Array.prototype.filter.call(arrayLike, function(s) {
					return s.indexOf("a") === 0;
				});
				assert.sameValue(Array.isArray(startsWithA), true, "generic filter returns JSArray");
				assert.sameValue(startsWithA.length, 2, "generic filter length");
				assert.sameValue(startsWithA[0], "apple", "generic filter[0]");
				assert.sameValue(startsWithA[1], "apricot", "generic filter[1]");

				// 6. Non-callable throws TypeError
				assert.throws(TypeError, function() {
					[1, 2].filter(123);
				}, "non-function throws TypeError");
			""");
		}

		@Test
		@DisplayName("test262: S22.1.3.27 - Array.prototype.sort")
		public void testArrayPrototypeSort() {
			runTest262("""
				/*---
				info: Array.prototype.sort stability, order, undefined and holes handling
				es6id: 22.1.3.27
				---*/
				// 1. Metadata
				assert.sameValue(typeof Array.prototype.sort, "function", "typeof sort === 'function'");
				assert.sameValue(Array.prototype.sort.name, "sort", "sort.name === 'sort'");
				assert.sameValue(Array.prototype.sort.length, 1, "sort.length === 1");

				// 2. Default Lexicographical sort
				var strSort = [10, 2, 5, 1].sort();
				assert.sameValue(strSort.join(","), "1,10,2,5", "default sort is lexicographical ('1' < '10' < '2' < '5')");

				// 3. Custom Comparator sort
				var numSort = [10, 2, 5, 1].sort(function(a, b) { return a - b; });
				assert.sameValue(numSort.join(","), "1,2,5,10", "custom comparator sort");

				// 4. In-place mutation check
				var orig = [3, 1, 2];
				var returned = orig.sort();
				assert.sameValue(orig === returned, true, "sort returns this");
				assert.sameValue(orig.join(","), "1,2,3", "mutated in place");

				// 5. Undefined sorted to the end before holes
				var withUndef = [3, undefined, 1, undefined, 2];
				withUndef.sort(function(a, b) { return a - b; });
				assert.sameValue(withUndef.length, 5, "length preserved");
				assert.sameValue(withUndef[0], 1, "sorted[0]");
				assert.sameValue(withUndef[1], 2, "sorted[1]");
				assert.sameValue(withUndef[2], 3, "sorted[2]");
				assert.sameValue(withUndef[3], undefined, "sorted[3] === undefined");
				assert.sameValue(withUndef[4], undefined, "sorted[4] === undefined");

				// 6. Holes sorted to the absolute end and remain holes
				var withHoles = new Array(3);
				withHoles[0] = 3;
				withHoles[2] = 1;
				withHoles.sort();
				assert.sameValue(withHoles.length, 3, "length preserved");
				assert.sameValue(withHoles[0], 1, "withHoles[0] === 1");
				assert.sameValue(withHoles[1], 3, "withHoles[1] === 3");
				assert.sameValue(withHoles.hasOwnProperty("2"), false, "slot 2 remains hole");

				// 7. Stable sort
				var items = [
					{ k: 1, v: "a" },
					{ k: 2, v: "b" },
					{ k: 1, v: "c" },
					{ k: 2, v: "d" }
				];
				items.sort(function(x, y) { return x.k - y.k; });
				assert.sameValue(items[0].v, "a", "stable sort item 0");
				assert.sameValue(items[1].v, "c", "stable sort item 1");
				assert.sameValue(items[2].v, "b", "stable sort item 2");
				assert.sameValue(items[3].v, "d", "stable sort item 3");

				// 8. Generic sort on array-like object
				var obj = { length: 3, 0: 30, 1: 10, 2: 20 };
				Array.prototype.sort.call(obj, function(a, b) { return a - b; });
				assert.sameValue(obj[0], 10, "generic sort obj[0]");
				assert.sameValue(obj[1], 20, "generic sort obj[1]");
				assert.sameValue(obj[2], 30, "generic sort obj[2]");
			""");
		}

		@Test
		@DisplayName("test262: Array.prototype General & Chaining Methods")
		public void testArrayPrototypeGeneralAndChaining() {
			runTest262("""
				/*---
				info: Array.prototype map, forEach, some, every, includes, indexOf, slice, chaining
				---*/
				// 1. Map
				var mapped = [1, 2, 3].map(function(x) { return x * 3; });
				assert.sameValue(mapped.join(","), "3,6,9", "map");

				// 2. Some & Every
				assert.sameValue([1, 2, 3].some(function(x) { return x === 2; }), true, "some true");
				assert.sameValue([1, 2, 3].some(function(x) { return x === 5; }), false, "some false");
				assert.sameValue([1, 2, 3].every(function(x) { return x > 0; }), true, "every true");
				assert.sameValue([1, 2, 3].every(function(x) { return x > 1; }), false, "every false");

				// 3. Includes & IndexOf (including NaN)
				assert.sameValue([1, 2, NaN].includes(NaN), true, "includes NaN");
				assert.sameValue([1, 2, 3].includes(2), true, "includes 2");
				assert.sameValue([1, 2, 3].includes(99), false, "includes 99");
				assert.sameValue(["a", "b", "a"].indexOf("a"), 0, "indexOf");
				assert.sameValue(["a", "b", "a"].lastIndexOf("a"), 2, "lastIndexOf");

				// 4. Slice & Splice & Concat
				var sliced = [1, 2, 3, 4, 5].slice(1, 4);
				assert.sameValue(sliced.join(","), "2,3,4", "slice");

				var spArr = [1, 2, 3, 4];
				var removed = spArr.splice(1, 2, 99, 100);
				assert.sameValue(spArr.join(","), "1,99,100,4", "splice mutated");
				assert.sameValue(removed.join(","), "2,3", "splice returned deleted");

				var concatted = [1, 2].concat([3, 4], 5);
				assert.sameValue(concatted.join(","), "1,2,3,4,5", "concat");

				// 5. Chaining
				var chainResult = [1, 2, 3, 4, 5]
					.filter(function(x) { return x % 2 === 1; })
					.map(function(x) { return x * 10; })
					.reduce(function(acc, x) { return acc + x; }, 0);
				assert.sameValue(chainResult, 90, "filter -> map -> reduce chaining: 10 + 30 + 50 = 90");
			""");
		}
	}

	@Nested
	@DisplayName("TC39 Test262: language/expressions/object")
	class LanguageExpressionsObject {

		@Test
		@DisplayName("test262: get-prop-desc - Property descriptor of 'get' accessor methods")
		public void testObjectLiteralGetterPropertyDescriptor() {
			runTest262("""
				/*---
				esid: sec-object-initializer-runtime-semantics-evaluation
				es6id: 12.2.6.8
				description: Property descriptor of "get" accessor methods
				---*/
				var obj = { get m() { return 1234; } };
				var desc = Object.getOwnPropertyDescriptor(obj, 'm');

				verifyProperty(obj, 'm', {
				  enumerable: true,
				  configurable: true
				});

				assert.sameValue(desc.value, undefined, 'The value of `desc.value` is `undefined`');
				assert.sameValue(desc.set, undefined, 'The value of `desc.set` is `undefined`');
				assert.sameValue(
				  typeof desc.get,
				  'function',
				  'The value of `typeof desc.get` is "function"'
				);
				assert.sameValue(desc.get(), 1234, '`desc.get()` returns `1234`');
			""");
		}

		@Test
		@DisplayName("test262: set-prop-desc - Property descriptor of 'set' accessor methods")
		public void testObjectLiteralSetterPropertyDescriptor() {
			runTest262("""
				/*---
				esid: sec-object-initializer-runtime-semantics-evaluation
				es6id: 12.2.6.8
				description: Property descriptor of "set" accessor methods
				---*/
				var stringSet;
				var obj = {
				  set m(param) {
				    stringSet = param;
				  }
				};
				var desc = Object.getOwnPropertyDescriptor(obj, 'm');

				verifyProperty(obj, 'm', {
				  enumerable: true,
				  configurable: true
				});

				assert.sameValue(desc.value, undefined, 'The value of `desc.value` is `undefined`');
				assert.sameValue(desc.get, undefined, 'The value of `desc.get` is `undefined`');
				assert.sameValue(
				  typeof desc.set,
				  'function',
				  'The value of `typeof desc.set` is "function"'
				);

				desc.set(1234);
				assert.sameValue(stringSet, 1234, 'The value of `stringSet` is `1234`');
			""");
		}
	}

	@Nested
	@DisplayName("TC39 Test262: annexB/Date")
	class AnnexBDateTest {

		@Test
		@DisplayName("test262: B.2.6 - Date.prototype.toGMTString data desc verification")
		public void testDatePrototypeToGMTStringDescriptor() {
			runTest262("""
				/*---
				es5id: B.2.6
				description: >
				    Object.getOwnPropertyDescriptor returns data desc for functions on
				    built-ins (Date.prototype.toGMTString)
				includes: [propertyHelper.js]
				---*/

				verifyProperty(Date.prototype, "toGMTString", {
				  enumerable: false,
				  writable: true,
				  configurable: true,
				});
			""");
		}

		@Test
		@DisplayName("test262: sec-array-constructor - Property descriptor of Array on global")
		public void testArrayConstructorDescriptor() {
			runTest262("""
				/*---
				esid: sec-array-constructor
				description: >
				  Property descriptor of Array
				includes: [propertyHelper.js]
				---*/

				verifyProperty(this, 'Array', {
				  value: Array,
				  writable: true,
				  enumerable: false,
				  configurable: true,
				});
			""");
		}
	}

	@Nested
	@DisplayName("TC39 Test262: built-ins/Array")
	class BuiltinArrayTest {

		@Test
		@DisplayName("test262: sec-array-len - Default [[Prototype]] value derived from realm of the NewTarget")
		public void testArrayLenRealmPrototype() {
			runTest262("""
				/*---
				esid: sec-array-len
				description: Default [[Prototype]] value derived from realm of the NewTarget.
				info: |
				  Array ( len )

				  ...
				  3. If NewTarget is undefined, let newTarget be the active function object; else let newTarget be NewTarget.
				  4. Let proto be ? GetPrototypeFromConstructor(newTarget, "%Array.prototype%").
				  5. Let array be ! ArrayCreate(0, proto).
				  ...
				  9. Return array.

				  GetPrototypeFromConstructor ( constructor, intrinsicDefaultProto )

				  ...
				  3. Let proto be ? Get(constructor, "prototype").
				  4. If Type(proto) is not Object, then
				    a. Let realm be ? GetFunctionRealm(constructor).
				    b. Set proto to realm's intrinsic object named intrinsicDefaultProto.
				  5. Return proto.
				features: [cross-realm, Reflect, Symbol]
				---*/

				var other = $262.createRealm().global;
				var newTarget = new other.Function();
				var arr;

				newTarget.prototype = undefined;
				arr = Reflect.construct(Array, [1], newTarget);
				assert.sameValue(Object.getPrototypeOf(arr), other.Array.prototype);

				newTarget.prototype = null;
				arr = Reflect.construct(Array, [1], newTarget);
				assert.sameValue(Object.getPrototypeOf(arr), other.Array.prototype);

				newTarget.prototype = true;
				arr = Reflect.construct(Array, [1], newTarget);
				assert.sameValue(Object.getPrototypeOf(arr), other.Array.prototype);

				newTarget.prototype = '';
				arr = Reflect.construct(Array, [1], newTarget);
				assert.sameValue(Object.getPrototypeOf(arr), other.Array.prototype);

				newTarget.prototype = Symbol();
				arr = Reflect.construct(Array, [1], newTarget);
				assert.sameValue(Object.getPrototypeOf(arr), other.Array.prototype);

				newTarget.prototype = 0;
				arr = Reflect.construct(Array, [1], newTarget);
				assert.sameValue(Object.getPrototypeOf(arr), other.Array.prototype);
			""");
		}

		@Test
		@DisplayName("test262: sec-array.from - Error advancing iterator via Symbol.iterator")
		public void testArrayFromIteratorError() {
			runTest262("""
				var items = {};
				items[Symbol.iterator] = function() {
				  return { next: function() { throw new Test262Error(); } };
				};
				assert.throws(Test262Error, function() { Array.from(items); });
			""");
		}
	}
}
