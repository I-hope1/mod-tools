package hope.magic.js.test;

import hope.magic.js.module.JSModule;
import hope.magic.js.module.JSModuleManager;
import hope.magic.js.runtime.JSContext;
import hope.magic.js.runtime.JSObject;
import hope.magic.js.runtime.JSOps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ModuleSystemTest {

	private JSContext cx;

	@BeforeEach
	void setUp() {
		cx = new JSContext();
	}

	@AfterEach
	void tearDown() {
		cx = null;
	}

	@Test
	void testBasicPropertyExports() {
		cx.registerModule("math", """
			exports.PI = 3.14159;
			exports.add = function(a, b) {
				return a + b;
			};
		""");

		Object result = cx.eval("""
			const math = require('math');
			math.PI + math.add(10, 20);
		""");

		assertEquals(33.14159, ((Number) result).doubleValue(), 1e-5);
	}

	@Test
	void testModuleExportsOverride() {
		cx.registerModule("calculator", """
			module.exports = function(a, b) {
				return a * b;
			};
		""");

		Object result = cx.eval("""
			const calc = require('calculator');
			calc(6, 7);
		""");

		assertEquals(42.0, ((Number) result).doubleValue(), 1e-5);
	}

	@Test
	void testScopeIsolation() {
		cx.registerModule("isolated", """
			var privateVar = 999;
			let privateLet = 888;
			const privateConst = 777;
			function privateFunc() { return 111; }
			exports.publicVal = 1;
		""");

		cx.eval("const m = require('isolated');");

		// 验证模块内变量未泄漏到全局
		assertEquals("undefined", cx.eval("typeof privateVar").toString());
		assertEquals("undefined", cx.eval("typeof privateLet").toString());
		assertEquals("undefined", cx.eval("typeof privateConst").toString());
		assertEquals("undefined", cx.eval("typeof privateFunc").toString());
	}

	@Test
	void testTopLevelReturn() {
		cx.registerModule("earlyReturn", """
			exports.status = "first";
			if (true) {
				return;
			}
			exports.status = "unreachable";
		""");

		Object result = cx.eval("""
			const m = require('earlyReturn');
			m.status;
		""");

		assertEquals("first", result.toString());
	}

	@Test
	void testFilenameAndDirname() {
		cx.registerModule("paths/myModule", """
			exports.file = __filename;
			exports.dir = __dirname;
		""");

		Object file = cx.eval("require('paths/myModule').file;");
		Object dir = cx.eval("require('paths/myModule').dir;");

		assertEquals("virtual:paths/myModule", file.toString());
		assertEquals("virtual:", dir.toString());
	}

	@Test
	void testCircularDependency() {
		cx.registerModule("circA", """
			exports.loaded = false;
			const b = require('circB');
			exports.bLoaded = b.loaded;
			exports.loaded = true;
		""");

		cx.registerModule("circB", """
			exports.loaded = false;
			const a = require('circA');
			exports.aLoaded = a.loaded;
			exports.loaded = true;
		""");

		cx.eval("const a = require('circA');");

		Object aLoaded = cx.eval("require('circA').loaded");
		Object bLoadedInA = cx.eval("require('circA').bLoaded");
		Object aLoadedInB = cx.eval("require('circB').aLoaded");
		Object bLoaded = cx.eval("require('circB').loaded");

		assertTrue((Boolean) aLoaded);
		assertTrue((Boolean) bLoadedInA);
		assertFalse((Boolean) aLoadedInB); // 循环依赖时，B require A 拿到的是 A 尚未完成时的状态 (loaded = false)
		assertTrue((Boolean) bLoaded);
	}

	@Test
	void testRequireCacheAndResolve() {
		cx.registerModule("cachedMod", """
			exports.count = 1;
		""");

		Object id = cx.eval("require.resolve('cachedMod');");
		assertEquals("virtual:cachedMod", id.toString());

		// 第一次 require
		cx.eval("const m1 = require('cachedMod'); m1.count = 42;");
		// 第二次 require，应该命中缓存拿到同一实例
		Object count = cx.eval("require('cachedMod').count;");
		assertEquals(42.0, ((Number) count).doubleValue(), 1e-5);
	}

	public static class HostMathService {
		public int square(int x) {
			return x * x;
		}
	}

	@Test
	void testJavaInstanceModuleRegistration() {
		cx.registerModuleInstance("host/math", new HostMathService());

		Object result = cx.eval("""
			const service = require('host/math');
			service.square(9);
		""");

		assertEquals(81.0, ((Number) result).doubleValue(), 1e-5);
	}

	@Test
	void testFileSystemResolution(@TempDir Path tempDir) throws IOException {
		// 创建嵌套目录与文件结构
		// tempDir/
		//   main.js
		//   config.json
		//   sub/
		//     helper.js
		//     packageDir/
		//       package.json
		//       customEntry.js
		Path mainJs = tempDir.resolve("main.js");
		Path configJson = tempDir.resolve("config.json");
		Path subDir = tempDir.resolve("sub");
		Files.createDirectories(subDir);
		Path helperJs = subDir.resolve("helper.js");
		Path packageDir = subDir.resolve("packageDir");
		Files.createDirectories(packageDir);
		Path packageJson = packageDir.resolve("package.json");
		Path customEntryJs = packageDir.resolve("customEntry.js");

		Files.writeString(configJson, "{\"appName\": \"MagicApp\", \"version\": 2}");
		Files.writeString(helperJs, """
			exports.multiply = function(x, y) { return x * y; };
		""");
		Files.writeString(packageJson, "{\"name\": \"pkg\", \"main\": \"customEntry.js\"}");
		Files.writeString(customEntryJs, """
			exports.entryMsg = "Hello from package.json main";
		""");

		Files.writeString(mainJs, """
			const cfg = require('./config.json');
			const helper = require('./sub/helper'); // 自动补全 .js
			const pkg = require('./sub/packageDir'); // 目录 package.json 解析

			exports.title = cfg.appName + " v" + cfg.version;
			exports.calc = helper.multiply(3, 4);
			exports.pkgMsg = pkg.entryMsg;
		""");

		JSModule module = cx.loadModule(mainJs.toString());
		assertNotNull(module);

		JSObject exp = (JSObject) module.getExports();
		assertEquals("MagicApp v2", exp.get("title").toString());
		assertEquals(12.0, ((Number) exp.get("calc")).doubleValue(), 1e-5);
		assertEquals("Hello from package.json main", exp.get("pkgMsg").toString());
	}

	@Test
	void testModuleNotFound() {
		Exception ex = assertThrows(RuntimeException.class, () -> {
			cx.eval("require('non_existent_module_xyz');");
		});
		assertTrue(ex.getMessage().contains("Cannot find module 'non_existent_module_xyz'"));
	}

	@Test
	void testEsmNamedExportsAndImports() {
		cx.registerModule("esmMath", """
			export const PI = 3.14159;
			export let count = 10;
			export function add(a, b) {
				return a + b;
			}
		""");

		Object result = cx.eval("""
			import { PI, count, add as myAdd } from 'esmMath';
			export const total = myAdd(PI, count);
		""");

		assertTrue(result instanceof JSObject);
		JSObject exp = (JSObject) result;
		assertEquals(13.14159, ((Number) exp.get("total")).doubleValue(), 1e-5);
	}

	@Test
	void testEsmDefaultExportAndImport() {
		cx.registerModule("esmCalc", """
			export default function(a, b) {
				return a * b;
			}
		""");

		Object result = cx.eval("""
			import multiply from 'esmCalc';
			export const res = multiply(6, 7);
		""");

		JSObject exp = (JSObject) result;
		assertEquals(42.0, ((Number) exp.get("res")).doubleValue(), 1e-5);
	}

	@Test
	void testEsmDefaultExportClass() {
		cx.registerModule("esmGreeter", """
			export default class Greeter {
				constructor(name) {
					this.name = name;
				}
				greet() {
					return "Hello, " + this.name + "!";
				}
			}
		""");

		Object result = cx.eval("""
			import Greeter from 'esmGreeter';
			const g = new Greeter("Antigravity");
			export const greeting = g.greet();
		""");

		JSObject exp = (JSObject) result;
		assertEquals("Hello, Antigravity!", exp.get("greeting").toString());
	}

	@Test
	void testEsmNamespaceImport() {
		cx.registerModule("esmUtils", """
			export const x = 100;
			export const y = 200;
			export function sum() { return x + y; }
		""");

		Object result = cx.eval("""
			import * as utils from 'esmUtils';
			export const total = utils.sum();
			export const xVal = utils.x;
		""");

		JSObject exp = (JSObject) result;
		assertEquals(300.0, ((Number) exp.get("total")).doubleValue(), 1e-5);
		assertEquals(100.0, ((Number) exp.get("xVal")).doubleValue(), 1e-5);
	}

	@Test
	void testEsmCombinedImport() {
		cx.registerModule("esmStyle", """
			export default 100;
			export const unit = "px";
		""");

		Object result = cx.eval("""
			import size, { unit } from 'esmStyle';
			export const formatted = "" + size + unit;
		""");

		JSObject exp = (JSObject) result;
		assertEquals("100px", exp.get("formatted").toString());
	}

	@Test
	void testEsmSideEffectImport() {
		cx.registerModule("sideEffectMod", """
			globalThis.sideEffectPassed = 999;
		""");

		Object result = cx.eval("""
			import 'sideEffectMod';
			export const val = globalThis.sideEffectPassed;
		""");

		JSObject exp = (JSObject) result;
		assertEquals(999.0, ((Number) exp.get("val")).doubleValue(), 1e-5);
	}

	@Test
	void testEsmReExport() {
		cx.registerModule("sourceMod", """
			export const a = 1;
			export const b = 2;
			export default function defaultFn() { return 42; }
		""");

		cx.registerModule("reexportMod", """
			export { a as alpha } from 'sourceMod';
			export * as allSource from 'sourceMod';
			export * from 'sourceMod';
		""");

		Object result = cx.eval("""
			import { alpha, b, allSource } from 'reexportMod';
			export const sum = alpha + b + allSource.a;
		""");

		JSObject exp = (JSObject) result;
		assertEquals(4.0, ((Number) exp.get("sum")).doubleValue(), 1e-5);
	}

	@Test
	void testEsmExportList() {
		cx.registerModule("exportListMod", """
			const val1 = "foo";
			const val2 = "bar";
			export { val1, val2 as alias2 };
		""");

		Object result = cx.eval("""
			import { val1, alias2 } from 'exportListMod';
			export const joined = val1 + ":" + alias2;
		""");

		JSObject exp = (JSObject) result;
		assertEquals("foo:bar", exp.get("joined").toString());
	}

	@Test
	void testEsmCircularDependency() {
		cx.registerModule("circEsmA", """
			import { getB } from 'circEsmB';
			export const nameA = "A";
			export function callB() {
				return getB();
			}
			export function getA() {
				return "RealA";
			}
		""");

		cx.registerModule("circEsmB", """
			import { getA } from 'circEsmA';
			export const nameB = "B";
			export function getB() {
				return "RealB";
			}
			export function callA() {
				return getA();
			}
		""");

		Object result = cx.eval("""
			import { callB, nameA } from 'circEsmA';
			import { callA, nameB } from 'circEsmB';
			export const bResult = callB();
			export const aResult = callA();
		""");

		JSObject exp = (JSObject) result;
		assertEquals("RealB", exp.get("bResult").toString());
		assertEquals("RealA", exp.get("aResult").toString());
	}

	@Test
	void testEsmCjsInterop() {
		// 1. ESM imports CJS module
		cx.registerModule("legacyCjs", """
			module.exports = function legacy(x) {
				return x * 10;
			};
		""");

		Object esmRes = cx.eval("""
			import legacy from 'legacyCjs';
			export const out = legacy(5);
		""");

		JSObject esmExp = (JSObject) esmRes;
		assertEquals(50.0, ((Number) esmExp.get("out")).doubleValue(), 1e-5);

		// 2. CJS requires ESM module
		cx.registerModule("modernEsm", """
			export const greeting = "hello";
			export default function sayHi() {
				return "hi";
			}
		""");

		Object cjsRes = cx.eval("""
			const mod = require('modernEsm');
			const greeting = mod.greeting;
			const defaultCall = mod.default();
			const isEsm = mod.__esModule;
			({ greeting, defaultCall, isEsm });
		""");

		JSObject cjsExp = (JSObject) cjsRes;
		assertEquals("hello", cjsExp.get("greeting").toString());
		assertEquals("hi", cjsExp.get("defaultCall").toString());
		assertTrue((Boolean) cjsExp.get("isEsm"));
	}

	@Test
	void testDynamicImportInAsyncFunction() {
		cx.registerModule("asyncTarget", """
			export function greet(who) {
				return "Welcome, " + who;
			}
		""");

		Object result = cx.eval("""
			async function run() {
				const m = await import('asyncTarget');
				return m.greet("Antigravity");
			}
			await run();
		""");

		assertEquals("Welcome, Antigravity", result.toString());
	}

	@Test
	void testDynamicImportWithPromiseThen() {
		cx.registerModule("promiseTarget", """
			export const number = 777;
		""");

		Object result = cx.eval("""
			let value = 0;
			import('promiseTarget').then(m => {
				value = m.number;
			});
			// 返回包含读取函数的对象
			({ getVal: () => value });
		""");

		JSObject obj = (JSObject) result;
		cx.drainMicrotasks();
		// 直接通过 Java 调用 getVal 方法
		hope.magic.js.runtime.JSFunction getVal = (hope.magic.js.runtime.JSFunction) obj.get("getVal");
		try {
			Object finalVal = getVal.call(cx, obj, new Object[0]);
			assertEquals(777.0, ((Number) finalVal).doubleValue(), 1e-5);
		} catch (Throwable t) {
			fail(t);
		}
	}

	@Test
	void testTopLevelAwaitInModule() {
		cx.registerModule("tlaSource", """
			export const asyncData = await Promise.resolve(42);
			export const computed = (await Promise.resolve(10)) * 2;
		""");

		Object result = cx.eval("""
			import { asyncData, computed } from 'tlaSource';
			export const sum = asyncData + computed;
		""");

		assertTrue(result instanceof JSObject);
		JSObject exp = (JSObject) result;
		assertEquals(62.0, ((Number) exp.get("sum")).doubleValue(), 1e-5);
	}

	@Test
	void testTopLevelAwaitInScript() {
		Object result = cx.eval("""
			const a = await Promise.resolve(100);
			const b = await Promise.resolve(200);
			a + b;
		""");
		assertEquals(300.0, ((Number) result).doubleValue(), 1e-5);
	}

	@Test
	void testTopLevelAwaitDefaultExport() {
		cx.registerModule("tlaDefault", """
			export default await Promise.resolve("magic_answer");
		""");

		Object result = cx.eval("""
			import val from 'tlaDefault';
			export const output = val;
		""");

		JSObject exp = (JSObject) result;
		assertEquals("magic_answer", exp.get("output"));
	}

	@Test
	void testTopLevelAwaitCjsRequire() {
		cx.registerModule("tlaForCjs", """
			export const num = await Promise.resolve(555);
		""");

		Object result = cx.eval("""
			const m = require('tlaForCjs');
			m.num;
		""");
		assertEquals(555.0, ((Number) result).doubleValue(), 1e-5);
	}
}
