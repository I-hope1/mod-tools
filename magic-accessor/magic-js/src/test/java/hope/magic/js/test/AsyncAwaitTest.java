package hope.magic.js.test;

import hope.magic.js.runtime.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class AsyncAwaitTest {

	@Test
	public void testPromiseBasicResolveAndThen() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
			"""
			let p = new Promise((resolve, reject) => {
			    resolve(21);
			});
			let p2 = p.then(x => x * 2);
			await p2;
			"""
		);

		System.out.println("DEBUG RES: " + res);
		Assertions.assertEquals(42.0, res);
	}

	@Test
	public void testPromiseCatchAndFinally() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
			"""
			let trace = [];
			let p = new Promise((resolve, reject) => {
			    reject("something went wrong");
			});
			p.then(val => {
			    trace.push("should not reach");
			}).catch(err => {
			    trace.push("caught: " + err);
			    return "recovered";
			}).finally(() => {
			    trace.push("finally");
			});
			trace;
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals(2, arr.length());
		Assertions.assertEquals("caught: something went wrong", arr.getElement(0));
		Assertions.assertEquals("finally", arr.getElement(1));
	}

	@Test
	public void testPromiseStaticMethods() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
			"""
			let res = {};

			Promise.all([Promise.resolve(1), Promise.resolve(2), 3]).then(arr => {
			    res.all = arr;
			});

			Promise.race([Promise.resolve("fast"), Promise.resolve("slow")]).then(val => {
			    res.race = val;
			});

			Promise.allSettled([Promise.resolve("ok"), Promise.reject("err")]).then(arr => {
			    res.settled = arr;
			});

			[res.all, res.race, res.settled];
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray out = (JSArray) res;

		// Promise.all 结果
		Assertions.assertInstanceOf(JSArray.class, out.getElement(0));
		JSArray allArr = (JSArray) out.getElement(0);
		Assertions.assertEquals(1.0, allArr.getElement(0));
		Assertions.assertEquals(2.0, allArr.getElement(1));
		Assertions.assertEquals(3.0, allArr.getElement(2));

		// Promise.race 结果
		Assertions.assertEquals("fast", out.getElement(1));

		// Promise.allSettled 结果
		Assertions.assertInstanceOf(JSArray.class, out.getElement(2));
		JSArray settledArr = (JSArray) out.getElement(2);
		Assertions.assertEquals(2, settledArr.length());
		JSObject item0 = (JSObject) settledArr.getElement(0);
		Assertions.assertEquals("fulfilled", item0.get("status"));
		Assertions.assertEquals("ok", item0.get("value"));
		JSObject item1 = (JSObject) settledArr.getElement(1);
		Assertions.assertEquals("rejected", item1.get("status"));
		Assertions.assertEquals("err", item1.get("reason"));
	}

	@Test
	public void testAsyncFunctionDeclaration() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
			"""
			async function add(a, b) {
			    return a + b;
			}
			let p = add(15, 27);
			p;
			"""
		);

		Assertions.assertInstanceOf(JSPromise.class, res);
		JSPromise promise = (JSPromise) res;
		Assertions.assertEquals(42.0, promise.getResult());
	}

	@Test
	public void testAsyncFunctionAwaitPromise() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
			"""
			async function compute() {
			    let a = await Promise.resolve(10);
			    let b = await Promise.resolve(20);
			    let c = await (a + b);
			    return c * 2;
			}
			await compute();
			"""
		);

		Assertions.assertEquals(60.0, res);
	}

	@Test
	public void testAsyncFunctionAwaitNonPromise() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
			"""
			async function wrap() {
			    let v = await 123;
			    return v + 1;
			}
			await wrap();
			"""
		);

		Assertions.assertEquals(124.0, res);
	}

	@Test
	public void testAsyncFunctionTryCatchAwaitRejection() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
			"""
			async function testCatch() {
			    try {
			        await Promise.reject("fatal error");
			        return "unreachable";
			    } catch (e) {
			        return "caught: " + e;
			    }
			}
			await testCatch();
			"""
		);

		Assertions.assertEquals("caught: fatal error", res);
	}

	@Test
	public void testAsyncArrowFunctions() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
			"""
			let singleParam = async x => x * 3;
			let multiParam = async (a, b) => {
			    let x = await Promise.resolve(a);
			    let y = await Promise.resolve(b);
			    return x + y;
			};

			let r1 = await singleParam(7);
			let r2 = await multiParam(10, 20);

			[r1, r2];
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals(21.0, arr.getElement(0));
		Assertions.assertEquals(30.0, arr.getElement(1));
	}

	@Test
	public void testAsyncClassAndObjectMethods() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
			"""
			class Calculator {
			    async doubleVal(x) {
			        let val = await Promise.resolve(x);
			        return val * 2;
			    }
			    static async staticAsync() {
			        return "static-res";
			    }
			}

			let obj = {
			    async getAnswer() {
			        let a = await Promise.resolve(42);
			        return a;
			    }
			};

			let calc = new Calculator();
			let r1 = await calc.doubleVal(25);
			let r2 = await Calculator.staticAsync();
			let r3 = await obj.getAnswer();

			[r1, r2, r3];
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals(50.0, arr.getElement(0));
		Assertions.assertEquals("static-res", arr.getElement(1));
		Assertions.assertEquals(42.0, arr.getElement(2));
	}

	@Test
	public void testSynchronousPrologueTiming() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
			"""
			let trace = [];
			async function fn() {
			    trace.push("prologue-start");
			    let v = await Promise.resolve(100);
			    trace.push("resumed: " + v);
			    return v;
			}

			trace.push("before-call");
			let p = fn();
			trace.push("after-call");
			await p;
			trace;
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		// 校验同步序言与异步恢复执行顺序：
		// 1. "before-call"
		// 2. "prologue-start" (在 await 前同步执行)
		// 3. "after-call" (await 挂起后控制权回到调用方)
		// 4. "resumed: 100" (脚本末尾 drainMicrotasks 触发恢复)
		Assertions.assertEquals(4, arr.length());
		Assertions.assertEquals("before-call", arr.getElement(0));
		Assertions.assertEquals("prologue-start", arr.getElement(1));
		Assertions.assertEquals("after-call", arr.getElement(2));
		Assertions.assertEquals("resumed: 100", arr.getElement(3));
	}

	@Test
	public void testQueueMicrotaskOrder() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
			"""
			let order = [];
			order.push("sync-1");
			queueMicrotask(() => {
			    order.push("micro-1");
			});
			Promise.resolve().then(() => {
			    order.push("promise-1");
			});
			order.push("sync-2");
			order;
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals(4, arr.length());
		Assertions.assertEquals("sync-1", arr.getElement(0));
		Assertions.assertEquals("sync-2", arr.getElement(1));
		Assertions.assertEquals("micro-1", arr.getElement(2));
		Assertions.assertEquals("promise-1", arr.getElement(3));
	}

	@Test
	public void testTopLevelAwait() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
			"""
			let p = Promise.resolve(99);
			let x = await p;
			x + 1;
			"""
		);

		Assertions.assertEquals(100.0, res);
	}
}
