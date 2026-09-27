package hope.magic.js.test;

import hope.magic.js.runtime.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class JSErrorStackTraceTest {

	@Test
	public void testBasicErrorStack() {
		JSContext cx = new JSContext();
		String code = """
			function foo() {
				return new Error("boom");
			}
			function bar() {
				return foo();
			}
			bar().stack;
		""";
		Object res = cx.eval(code, "test_app.js");
		Assertions.assertInstanceOf(String.class, res);
		String stack = (String) res;
		Assertions.assertTrue(stack.startsWith("Error: boom\n"), "Stack should start with Error: boom, got:\n" + stack);
		Assertions.assertTrue(stack.contains("at foo (test_app.js:2)"), "Stack should contain foo frame, got:\n" + stack);
		Assertions.assertTrue(stack.contains("at bar (test_app.js:5)"), "Stack should contain bar frame, got:\n" + stack);
	}

	@Test
	public void testErrorStackWithoutMessage() {
		JSContext cx = new JSContext();
		Object res = cx.eval("""
			function testNoMsg() {
				return new Error();
			}
			testNoMsg().stack;
		""", "no_msg.js");
		Assertions.assertInstanceOf(String.class, res);
		String stack = (String) res;
		Assertions.assertTrue(stack.startsWith("Error\n    at testNoMsg (no_msg.js:2)"), "Unexpected stack header:\n" + stack);
	}

	@Test
	public void testTypeErrorStack() {
		JSContext cx = new JSContext();
		Object res = cx.eval("""
			function checkType() {
				throw new TypeError("bad type");
			}
			let s;
			try {
				checkType();
			} catch (e) {
				s = e.stack;
			}
			s;
		""", "type_error.js");
		Assertions.assertInstanceOf(String.class, res);
		String stack = (String) res;
		Assertions.assertTrue(stack.startsWith("TypeError: bad type\n"), "Unexpected TypeError header:\n" + stack);
		Assertions.assertTrue(stack.contains("at checkType (type_error.js:2)"), "Missing checkType frame:\n" + stack);
	}

	@Test
	public void testStackPropertyNonEnumerable() {
		JSContext cx = new JSContext();
		Object res = cx.eval("""
			let err = new Error("hidden");
			let keys = Object.keys(err);
			keys.includes("stack");
		""");
		Assertions.assertEquals(Boolean.FALSE, res);
	}

	@Test
	public void testStackPropertyMutable() {
		JSContext cx = new JSContext();
		Object res = cx.eval("""
			let err = new Error("before");
			err.stack = "custom stack trace string";
			err.stack;
		""");
		Assertions.assertEquals("custom stack trace string", res);
	}

	@Test
	public void testErrorCaptureStackTrace() {
		JSContext cx = new JSContext();
		String code = """
			function CustomError(msg) {
				this.name = 'CustomError';
				this.message = msg;
				Error.captureStackTrace(this, CustomError);
			}
			function trigger() {
				throw new CustomError("custom failure");
			}
			let s;
			try {
				trigger();
			} catch (e) {
				s = e.stack;
			}
			s;
		""";
		Object res = cx.eval(code, "capture.js");
		Assertions.assertInstanceOf(String.class, res);
		String stack = (String) res;
		Assertions.assertTrue(stack.startsWith("CustomError: custom failure\n"), "Unexpected header:\n" + stack);
		Assertions.assertFalse(stack.contains("at CustomError"), "CustomError frame should be omitted:\n" + stack);
		Assertions.assertTrue(stack.contains("at trigger (capture.js:7)"), "Missing trigger frame:\n" + stack);
	}

	@Test
	public void testStackTraceLimit() {
		JSContext cx = new JSContext();
		String code = """
			Error.stackTraceLimit = 2;
			function a() { return b(); }
			function b() { return c(); }
			function c() { return new Error("limited"); }
			let s = a().stack;
			Error.stackTraceLimit = 10;
			s;
		""";
		Object res = cx.eval(code, "limit.js");
		Assertions.assertInstanceOf(String.class, res);
		String stack = (String) res;
		String[] lines = stack.split("\n");
		// 1 line for header + 2 lines for frames = 3 lines total
		Assertions.assertEquals(3, lines.length, "Expected 3 lines (1 header + 2 frames), got:\n" + stack);
		Assertions.assertTrue(lines[1].contains("at c (limit.js:4)"));
		Assertions.assertTrue(lines[2].contains("at b (limit.js:3)"));
	}

	@Test
	public void testJSExceptionGetMessageContainsStackTrace() {
		JSContext cx = new JSContext();
		try {
			cx.eval("""
				function failDeeply() {
					throw new RangeError("index out of range");
				}
				failDeeply();
			""", "fail_test.js");
			Assertions.fail("Expected JSException to be thrown");
		} catch (JSOps.JSException e) {
			String message = e.getMessage();
			Assertions.assertTrue(message.contains("RangeError: index out of range"), "Message should contain error:\n" + message);
			Assertions.assertTrue(message.contains("at failDeeply (fail_test.js:2)"), "Message should contain call frame:\n" + message);
		}
	}
}
