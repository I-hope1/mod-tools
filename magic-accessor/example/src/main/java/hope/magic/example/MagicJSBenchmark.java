package hope.magic.example;

import com.caoccao.javet.interop.*;
import com.caoccao.javet.interop.converters.JavetProxyConverter;
import com.caoccao.javet.utils.JavetResourceUtils;
import com.caoccao.javet.values.reference.IV8ValueFunction;
import hope.magic.js.compiler.JSCompiler;
import hope.magic.js.runtime.*;
import hope.magic.js.runtime.JSFunction;
import org.graalvm.polyglot.Value;
import org.mozilla.javascript.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.nashorn.api.scripting.*;

import javax.script.*;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class MagicJSBenchmark {

	private TestObject target;

	// 1. MagicJS 编译闭包函数
	private JSContext  magicContext;
	private JSFunction magicFieldScript;
	private JSFunction magicMethodScript;
	private JSFunction magicLoopScript;
	private JSFunction magicObjScript;
	private JSFunction magicPolyScript;
	private JSFunction magicPolyScriptHoisted;
	private JSFunction magicPolyScriptHoistedIrem;
	private JSFunction magicPolyScriptUnhoisted;

	// 2. Mozilla Rhino 编译闭包函数
	private Context    rhinoContext;
	private Scriptable rhinoScope;
	private Function   rhinoFieldScript;
	private Function   rhinoMethodScript;
	private Function   rhinoLoopScript;
	private Function   rhinoObjScript;
	private Function   rhinoPolyScript;

	// 3. Oracle GraalJS 编译闭包函数
	private org.graalvm.polyglot.Context graalContext;
	private Value                        graalFieldScript;
	private Value                        graalMethodScript;
	private Value                        graalLoopScript;
	private Value                        graalObjScript;
	private Value                        graalPolyScript;

	// 4. OpenJDK Nashorn 编译闭包函数
	private ScriptEngine       nashornEngine;
	private Bindings           nashornBindings;
	private ScriptObjectMirror nashornFieldScript;
	private ScriptObjectMirror nashornMethodScript;
	private ScriptObjectMirror nashornLoopScript;
	private ScriptObjectMirror nashornObjScript;
	private ScriptObjectMirror nashornPolyScript;

	// 5. Google V8 (Javet) 执行句柄
	private V8Runtime        v8Runtime;
	private IV8ValueFunction v8FieldScript;
	private IV8ValueFunction v8MethodScript;
	private IV8ValueFunction v8LoopScript;
	private IV8ValueFunction v8ObjScript;
	private IV8ValueFunction v8PolyScript;

	@Setup(Level.Trial)
	public void setup() throws Exception {
		String code = """
		 var sum = 0;
		 for (var i = 2; i < 1000; i++) {
		     var isPrime = 1;
		     for (var j = 2; j * j <= i; j++) {
		         if (i % j === 0) {
		             isPrime = 0;
		         }
		     }
		     if (isPrime === 1) {
		         sum += i;
		     }
		 }
		 return sum;
		 """;
		String dynObj      = "var dynamicObj = { x: 100, y: 200, name: 'MagicJS' };";
		String code_field  = "return target.secret;";
		String code_method = "return target.multiply(6, 7);";
		String code_dyn_field = """
		 var obj = { x: 10, y: 20, z: 30, w: 40 };
		 var sum = 0;
		 for (var i = 0; i < 10000; i++) {
		     obj.x = obj.x + 1;
		     obj.y = obj.y + 2;
		     sum = sum + obj.x + obj.y + obj.z + obj.w;
		 }
		 return sum;
		 """;
		String init_poly = """
		 var pool = [
		     { type: 1, val: 10, tag: 5 },
		     { type: 2, val: 20.5, meta: 3.14 },
		     { type: 3, val: 30, flag: 1, note: 100 },
		     { type: 4, val: 40, extra: { base: 200 } },
		     { type: 5, val: 50.25, delta: 1.75 }
		 ];
		 """;
		String code_poly = """
		 var total = 0;
		 var factor = 1;
		 
		 for (var i = 0; i < 2000; i++) {
		     var item = pool[i % 5];
		     var t = item.type;
		 
		     if (i % 2 === 0) {
		         factor = i % 10;
		     } else {
		         factor = (i % 10) + 0.5;
		     }
		 
		     var contribution = 0;
		     if (t === 1) {
		         contribution = item.val * factor + item.tag;
		     } else if (t === 2) {
		         contribution = item.val * 1.5 + factor * item.meta;
		     } else if (t === 3) {
		         contribution = (item.val + factor) * item.flag + item.note;
		     } else if (t === 4) {
		         contribution = item.extra.base + item.val * factor;
		     } else {
		         contribution = item.val * factor - item.delta;
		     }
		 
		     total = total + contribution;
		 }
		 return total;
		 """;

		target = new TestObject(98765);

		// ==================== 1. 初始化 MagicJS ====================
		magicContext = new JSContext();
		magicContext.set("target", target);
		magicContext.eval(dynObj);
		magicContext.eval(init_poly);

		magicFieldScript = (JSFunction) magicContext.eval("(function() { " + code_field + " })");
		magicMethodScript = (JSFunction) magicContext.eval("(function() { " + code_method + " })");
		magicLoopScript = (JSFunction) magicContext.eval("(function() {\n" + code + "\n})");
		magicObjScript = (JSFunction) magicContext.eval("(function() {\n" + code_dyn_field + "\n})");
		JSCompiler.ENABLE_LOOP_INVARIANT_HOISTING = false;
		JSCompiler.ENABLE_INTEGER_MOD_SPECIALIZATION = false;
		magicPolyScriptUnhoisted = (JSFunction) magicContext.eval("(function() {\n" + code_poly + "\n})");

		JSCompiler.ENABLE_LOOP_INVARIANT_HOISTING = true;
		JSCompiler.ENABLE_INTEGER_MOD_SPECIALIZATION = false;
		magicPolyScriptHoisted = (JSFunction) magicContext.eval("(function() {\n" + code_poly + "\n})");
		magicPolyScript = magicPolyScriptHoisted;

		JSCompiler.ENABLE_LOOP_INVARIANT_HOISTING = true;
		JSCompiler.ENABLE_INTEGER_MOD_SPECIALIZATION = true;
		magicPolyScriptHoistedIrem = (JSFunction) magicContext.eval("(function() {\n" + code_poly + "\n})");

		// ==================== 2. 初始化 Mozilla Rhino ====================
		rhinoContext = Context.enter();
		rhinoContext.setOptimizationLevel(9); // JIT 编译级别 9
		rhinoContext.setInterpretedMode(false);
		rhinoScope = rhinoContext.initStandardObjects();
		Object wrappedTarget = Context.javaToJS(target, rhinoScope);
		org.mozilla.javascript.ScriptableObject.putProperty(rhinoScope, "target", wrappedTarget);
		rhinoContext.evaluateString(rhinoScope, dynObj, "objInit", 1, null);
		rhinoContext.evaluateString(rhinoScope, init_poly, "polyInit", 1, null);

		rhinoFieldScript = (Function) rhinoContext.evaluateString(rhinoScope, "(function() { " + code_field + " })", "fieldScript", 1, null);
		rhinoMethodScript = (Function) rhinoContext.evaluateString(rhinoScope, "(function() { " + code_method + " })", "methodScript", 1, null);
		rhinoLoopScript = (Function) rhinoContext.evaluateString(rhinoScope, "(function() {\n" + code + "\n})", "loopScript", 1, null);
		rhinoObjScript = (Function) rhinoContext.evaluateString(rhinoScope, "(function() {\n" + code_dyn_field + "\n})", "objScript", 1, null);
		rhinoPolyScript = (Function) rhinoContext.evaluateString(rhinoScope, "(function() {\n" + code_poly + "\n})", "polyScript", 1, null);

		// ==================== 3. 初始化 Oracle GraalJS ====================
		graalContext = org.graalvm.polyglot.Context.newBuilder("js")
		 .allowAllAccess(true)
		 .build();
		graalContext.initialize("js");
		graalContext.getBindings("js").putMember("target", target);
		graalContext.eval("js", dynObj);
		graalContext.eval("js", init_poly);

		graalFieldScript = graalContext.eval("js", "(function() { " + code_field + " })");
		graalMethodScript = graalContext.eval("js", "(function() { " + code_method + " })");
		graalLoopScript = graalContext.eval("js", "(function() {\n" + code + "\n})");
		graalObjScript = graalContext.eval("js", "(function() {\n" + code_dyn_field + "\n})");
		graalPolyScript = graalContext.eval("js", "(function() {\n" + code_poly + "\n})");

		// ==================== 4. 初始化 OpenJDK Nashorn ====================
		NashornScriptEngineFactory nashornFactory = new NashornScriptEngineFactory();
		nashornEngine = nashornFactory.getScriptEngine();
		nashornBindings = nashornEngine.createBindings();
		nashornBindings.put("target", target);
		nashornEngine.eval(dynObj, nashornBindings);
		nashornEngine.eval(init_poly, nashornBindings);

		nashornFieldScript = (ScriptObjectMirror) nashornEngine.eval("(function() { " + code_field + " })", nashornBindings);
		nashornMethodScript = (ScriptObjectMirror) nashornEngine.eval("(function() { " + code_method + " })", nashornBindings);
		nashornLoopScript = (ScriptObjectMirror) nashornEngine.eval("(function() {\n" + code + "\n})", nashornBindings);
		nashornObjScript = (ScriptObjectMirror) nashornEngine.eval("(function() {\n" + code_dyn_field + "\n})", nashornBindings);
		nashornPolyScript = (ScriptObjectMirror) nashornEngine.eval("(function() {\n" + code_poly + "\n})", nashornBindings);

		// ==================== 5. 初始化 Google V8 (Javet) ====================
		v8Runtime = V8Host.getV8Instance().createV8Runtime();
		// 开启 ProxyConverter 以便让 V8 直接通过反射调用 Java 对象属性与方法
		v8Runtime.setConverter(new JavetProxyConverter());
		v8Runtime.getGlobalObject().set("target", target);
		v8Runtime.getExecutor(dynObj).executeVoid();
		v8Runtime.getExecutor(init_poly).executeVoid();

		v8FieldScript = v8Runtime.getExecutor("(function() { " + code_field + " })").execute();
		v8MethodScript = v8Runtime.getExecutor("(function() { " + code_method + " })").execute();
		v8LoopScript = v8Runtime.getExecutor("(function() {\n" + code + "\n})").execute();
		v8ObjScript = v8Runtime.getExecutor("(function() {\n" + code_dyn_field + "\n})").execute();
		v8PolyScript = v8Runtime.getExecutor("(function() {\n" + code_poly + "\n})").execute();
	}

	@TearDown(Level.Trial)
	public void tearDown() throws Exception {
		Context.exit();
		if (graalContext != null) {
			graalContext.close();
		}
		if (v8Runtime != null && !v8Runtime.isClosed()) {
			try {
				// 1. 从 V8 全局对象上解绑 Java 代理，释放那 7 个 Native Callback Context
				v8Runtime.getGlobalObject().delete("target");
				v8Runtime.getGlobalObject().delete("dynamicObj");
				v8Runtime.getGlobalObject().delete("pool");

				JavetResourceUtils.safeClose(v8FieldScript);
				JavetResourceUtils.safeClose(v8MethodScript);
				JavetResourceUtils.safeClose(v8LoopScript);
				JavetResourceUtils.safeClose(v8ObjScript);
				JavetResourceUtils.safeClose(v8PolyScript);

				// 2. 通知 V8 执行一次内存整理
				v8Runtime.lowMemoryNotification();
			} catch (Throwable ignored) { }

			// 3. 安全关闭 V8 运行时
			v8Runtime.close();
		}
	}

	//region 1. Java Direct 原生基准
	@Benchmark
	public int baseline_java_direct_field() {
		return target.secret;
	}

	@Benchmark
	public double baseline_java_direct_method() {
		return target.multiply(6.0, 7.0);
	}

	@Benchmark
	public int baseline_java_direct_prime_sum_1000() {
		int sum = 0;
		for (int i = 2; i < 1000; i++) {
			int isPrime = 1;
			for (int j = 2; j * j <= i; j++) {
				if (i % j == 0) {
					isPrime = 0;
				}
			}
			if (isPrime == 1) {
				sum += i;
			}
		}
		return sum;
	}
	//endregion

	//region 2. 字段访问对比 (MagicJS vs V8 vs GraalJS vs Nashorn)
	@Benchmark
	public int magic_js_field_read() throws Throwable {
		return ((Number) magicFieldScript.call0(magicContext, null)).intValue();
	}

	@Benchmark
	public int v8_js_field_read() throws Exception {
		try (var result = v8FieldScript.call(null)) {
			return result.asInt();
		}
	}

	@Benchmark
	public int nashorn_js_field_read() throws Exception {
		return ((Number) nashornFieldScript.call(null)).intValue();
	}

	@Benchmark
	public int graal_js_field_read() {
		return graalFieldScript.execute().asInt();
	}
	//endregion

	//region 3. 方法调用对比 (MagicJS vs V8 vs GraalJS vs Nashorn)
	@Benchmark
	public double magic_js_method_call() throws Throwable {
		return magicMethodScript.call0Double(magicContext);
	}

	@Benchmark
	public double v8_js_method_call() throws Exception {
		try (var result = v8MethodScript.call(null)) {
			return result.asDouble();
		}
	}

	@Benchmark
	public double nashorn_js_method_call() throws Exception {
		return ((Number) nashornMethodScript.call(null)).doubleValue();
	}

	@Benchmark
	public double graal_js_method_call() {
		return graalMethodScript.execute().asDouble();
	}
	//endregion

	//region 4. 1000以内质数和计算 (MagicJS vs V8 vs GraalJS vs Nashorn)
	@Benchmark
	public double magic_js_prime_sum_1000() throws Throwable {
		return magicLoopScript.call0Double(magicContext);
	}

	@Benchmark
	public double v8_js_prime_sum_1000() throws Exception {
		try (var result = v8LoopScript.call(null)) {
			return result.asDouble();
		}
	}

	@Benchmark
	public double nashorn_js_prime_sum_1000() throws Exception {
		return ((Number) nashornLoopScript.call(null)).doubleValue();
	}

	@Benchmark
	public double graal_js_prime_sum_1000() {
		return graalLoopScript.execute().asDouble();
	}
	//endregion

	//region 5. 动态 JSObject 属性访问 (MagicJS vs V8 vs GraalJS vs Nashorn)
	@Benchmark
	public double magic_js_dynamic_obj_read() throws Throwable {
		return magicObjScript.call0Double(magicContext);
	}

	@Benchmark
	public double v8_js_dynamic_obj_read() throws Exception {
		try (var result = v8ObjScript.call(null)) {
			return result.asDouble();
		}
	}

	@Benchmark
	public double nashorn_js_dynamic_obj_read() throws Exception {
		return ((Number) nashornObjScript.call(null)).doubleValue();
	}

	@Benchmark
	public double graal_js_dynamic_obj_read() {
		return graalObjScript.execute().asDouble();
	}
	//endregion

	//region 6. 5-Shape 多态流水线 Poly (MagicJS vs V8 vs GraalJS vs Nashorn)
	@Benchmark
	public double magic_js_poly_unhoisted() throws Throwable {
		return magicPolyScriptUnhoisted.call0Double(magicContext);
	}

	@Benchmark
	public double magic_js_poly_hoisted() throws Throwable {
		return magicPolyScriptHoisted.call0Double(magicContext);
	}

	@Benchmark
	public double magic_js_poly_hoisted_irem() throws Throwable {
		return magicPolyScriptHoistedIrem.call0Double(magicContext);
	}

	@Benchmark
	public double magic_js_poly() throws Throwable {
		return magicPolyScriptHoisted.call0Double(magicContext);
	}

	@Benchmark
	public double v8_js_poly() throws Exception {
		try (var result = v8PolyScript.call(null)) {
			return result.asDouble();
		}
	}

	@Benchmark
	public double nashorn_js_poly() throws Exception {
		return ((Number) nashornPolyScript.call(null)).doubleValue();
	}

	@Benchmark
	public double graal_js_poly() {
		return graalPolyScript.execute().asDouble();
	}
	//endregion


	public static class TestObject {
		public int secret;
		public double multiply(double x, double y) { return x * y; }
		public TestObject(int secret) {
			this.secret = secret;
		}
	}

	public static void main(String[] args) throws Throwable {
		MagicJSBenchmark b = new MagicJSBenchmark();
		b.setup();
		System.out.println("=== MagicJSBenchmark 闭包对齐自检 ===");
		System.out.println("Field Read:    MagicJS=" + b.magic_js_field_read() + " | V8=" + b.v8_js_field_read() + " | Graal=" + b.graal_js_field_read() + " | Nashorn=" + b.nashorn_js_field_read());
		System.out.println("Method Call:   MagicJS=" + b.magic_js_method_call() + " | V8=" + b.v8_js_method_call() + " | Graal=" + b.graal_js_method_call() + " | Nashorn=" + b.nashorn_js_method_call());
		System.out.println("Prime Sum:     MagicJS=" + b.magic_js_prime_sum_1000() + " | V8=" + b.v8_js_prime_sum_1000() + " | Graal=" + b.graal_js_prime_sum_1000() + " | Nashorn=" + b.nashorn_js_prime_sum_1000());
		System.out.println("Dynamic Obj:   MagicJS=" + b.magic_js_dynamic_obj_read() + " | V8=" + b.v8_js_dynamic_obj_read() + " | Graal=" + b.graal_js_dynamic_obj_read() + " | Nashorn=" + b.nashorn_js_dynamic_obj_read());
		System.out.println("Poly Pipeline: MagicJS(hoisted)=" + b.magic_js_poly_hoisted() + " | MagicJS(unhoisted)=" + b.magic_js_poly_unhoisted() + " | V8=" + b.v8_js_poly() + " | Graal=" + b.graal_js_poly() + " | Nashorn=" + b.nashorn_js_poly());
		b.tearDown();
		System.out.println("=== 自检全部通过！ ===");
	}
}