package hope.magic.example;

import com.caoccao.javet.interop.*;
import com.caoccao.javet.interop.converters.JavetProxyConverter;
import com.caoccao.javet.interop.executors.IV8Executor;
import hope.magic.js.compiler.JSCompiler;
import hope.magic.js.runtime.JSContext;
import hope.magic.js.runtime.JSScript;
import org.graalvm.polyglot.Source;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.openjdk.jmh.annotations.*;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;

import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.Bindings;
import javax.script.ScriptEngine;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class MagicJSBenchmark {

	private TargetObject target;

	// 1. MagicJS 编译脚本
	private JSContext magicContext;
	private JSScript  magicFieldScript;
	private JSScript  magicMethodScript;
	private JSScript  magicLoopScript;
	private JSScript  magicObjScript;
	private JSScript  magicPolyScript;

	// 2. Mozilla Rhino 编译脚本
	private Context    rhinoContext;
	private Scriptable rhinoScope;
	private Script     rhinoFieldScript;
	private Script     rhinoMethodScript;
	private Script     rhinoLoopScript;
	private Script     rhinoObjScript;
	private Script     rhinoPolyScript;

	// 3. Oracle GraalJS 编译脚本
	private org.graalvm.polyglot.Context graalContext;
	private Source                       graalFieldScript;
	private Source                       graalMethodScript;
	private Source                       graalLoopScript;
	private Source                       graalObjScript;
	private Source                       graalPolyScript;

	// 4. OpenJDK Nashorn 编译脚本
	private ScriptEngine   nashornEngine;
	private Bindings       nashornBindings;
	private CompiledScript nashornFieldScript;
	private CompiledScript nashornMethodScript;
	private CompiledScript nashornLoopScript;
	private CompiledScript nashornObjScript;
	private CompiledScript nashornPolyScript;

	// 5. Google V8 (Javet) 执行句柄
	private V8Runtime   v8Runtime;
	private IV8Executor v8FieldScript;
	private IV8Executor v8MethodScript;
	private IV8Executor v8LoopScript;
	private IV8Executor v8ObjScript;
	private IV8Executor v8PolyScript;

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
		 sum;
		 """;
		String dynObj         = "var dynamicObj = { x: 100, y: 200, name: 'MagicJS' };";
		String code_field     = "target.secretCode;";
		String code_method    = "target.multiply(6, 7);";
		String code_dyn_field = "dynamicObj.x;";
		String code_poly = """
		 var pool = [
		     { type: 1, val: 10, tag: 5 },
		     { type: 2, val: 20.5, meta: 3.14 },
		     { type: 3, val: 30, flag: 1, note: 100 },
		     { type: 4, val: 40, extra: { base: 200 } },
		     { type: 5, val: 50.25, delta: 1.75 }
		 ];
		 
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
		 total;
		 """;

		target = MagicAccessorSample.newTargetObject(98765, "Benchmark Object");

		// ==================== 1. 初始化 MagicJS ====================
		magicContext = new JSContext();
		magicContext.set("target", target);
		magicContext.eval(dynObj);

		magicFieldScript = JSCompiler.compile(code_field);
		magicMethodScript = JSCompiler.compile(code_method);
		magicLoopScript = JSCompiler.compile(code);
		magicObjScript = JSCompiler.compile(code_dyn_field);
		magicPolyScript = JSCompiler.compile(code_poly);

		// ==================== 2. 初始化 Mozilla Rhino ====================
		rhinoContext = Context.enter();
		rhinoContext.setOptimizationLevel(9); // JIT 编译级别 9
		rhinoContext.setInterpretedMode(false);
		rhinoScope = rhinoContext.initStandardObjects();
		Object wrappedTarget = Context.javaToJS(target, rhinoScope);
		org.mozilla.javascript.ScriptableObject.putProperty(rhinoScope, "target", wrappedTarget);
		rhinoContext.evaluateString(rhinoScope, dynObj, "objInit", 1, null);

		rhinoFieldScript = rhinoContext.compileString(code_field, "fieldScript", 1, null);
		rhinoMethodScript = rhinoContext.compileString(code_method, "methodScript", 1, null);
		rhinoLoopScript = rhinoContext.compileString(code, "loopScript", 1, null);
		rhinoObjScript = rhinoContext.compileString(code_dyn_field, "objScript", 1, null);
		rhinoPolyScript = rhinoContext.compileString(code_poly, "polyScript", 1, null);

		// ==================== 3. 初始化 Oracle GraalJS ====================
		graalContext = org.graalvm.polyglot.Context.newBuilder("js")
		 .allowAllAccess(true)
		 .build();
		graalContext.initialize("js");
		graalContext.getBindings("js").putMember("target", target);
		graalContext.eval("js", dynObj);

		graalFieldScript = Source.newBuilder("js", code_field, "fieldScript").cached(true).build();
		graalMethodScript = Source.newBuilder("js", code_method, "methodScript").cached(true).build();
		graalLoopScript = Source.newBuilder("js", code, "loopScript").cached(true).build();
		graalObjScript = Source.newBuilder("js", code_dyn_field, "objScript").cached(true).build();
		graalPolyScript = Source.newBuilder("js", code_poly, "polyScript").cached(true).build();

		// ==================== 4. 初始化 OpenJDK Nashorn ====================
		NashornScriptEngineFactory nashornFactory = new NashornScriptEngineFactory();
		nashornEngine = nashornFactory.getScriptEngine();
		Compilable compilable = (Compilable) nashornEngine;
		nashornBindings = nashornEngine.createBindings();
		nashornBindings.put("target", target);
		nashornEngine.eval(dynObj, nashornBindings);

		nashornFieldScript = compilable.compile(code_field);
		nashornMethodScript = compilable.compile(code_method);
		nashornLoopScript = compilable.compile(code);
		nashornObjScript = compilable.compile(code_dyn_field);
		nashornPolyScript = compilable.compile(code_poly);

		// ==================== 5. 初始化 Google V8 (Javet) ====================
		v8Runtime = V8Host.getV8Instance().createV8Runtime();
		// 开启 ProxyConverter 以便让 V8 直接通过反射调用 Java 对象属性与方法
		v8Runtime.setConverter(new JavetProxyConverter());
		v8Runtime.getGlobalObject().set("target", target);
		v8Runtime.getExecutor(dynObj).executeVoid();

		v8FieldScript = v8Runtime.getExecutor(code_field);
		v8MethodScript = v8Runtime.getExecutor(code_method);
		v8LoopScript = v8Runtime.getExecutor(code);
		v8ObjScript = v8Runtime.getExecutor(code_dyn_field);
		v8PolyScript = v8Runtime.getExecutor(code_poly);
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
		return target.getSecretCode();
	}

	@Benchmark
	public int baseline_java_direct_method() {
		return MagicAccessorSample.callMultiply(target, 6, 7);
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
	public double magic_js_field_read() throws Throwable {
		return magicFieldScript.runDouble(magicContext);
	}

	@Benchmark
	public int v8_js_field_read() throws Exception {
		return v8FieldScript.executeInteger();
	}

	@Benchmark
	public Object nashorn_js_field_read() throws Exception {
		return nashornFieldScript.eval(nashornBindings);
	}

	@Benchmark
	public Object graal_js_field_read() {
		return graalContext.eval(graalFieldScript);
	}
	//endregion

	//region 3. 方法调用对比 (MagicJS vs V8 vs GraalJS vs Nashorn)
	@Benchmark
	public double magic_js_method_call() throws Throwable {
		return magicMethodScript.runDouble(magicContext);
	}

	@Benchmark
	public int v8_js_method_call() throws Exception {
		return v8MethodScript.executeInteger();
	}

	@Benchmark
	public Object nashorn_js_method_call() throws Exception {
		return nashornMethodScript.eval(nashornBindings);
	}

	@Benchmark
	public Object graal_js_method_call() {
		return graalContext.eval(graalMethodScript);
	}
	//endregion

	//region 4. 1000以内质数和计算 (MagicJS vs V8 vs GraalJS vs Nashorn)
	@Benchmark
	public double magic_js_prime_sum_1000() throws Throwable {
		return magicLoopScript.runDouble(magicContext);
	}

	@Benchmark
	public double v8_js_prime_sum_1000() throws Exception {
		return v8LoopScript.executeDouble();
	}

	@Benchmark
	public Object nashorn_js_prime_sum_1000() throws Exception {
		return nashornLoopScript.eval(nashornBindings);
	}

	@Benchmark
	public Object graal_js_prime_sum_1000() {
		return graalContext.eval(graalLoopScript);
	}
	//endregion

	//region 5. 动态 JSObject 属性访问 (MagicJS vs V8 vs GraalJS vs Nashorn)
	@Benchmark
	public double magic_js_dynamic_obj_read() throws Throwable {
		return magicObjScript.runDouble(magicContext);
	}

	@Benchmark
	public double v8_js_dynamic_obj_read() throws Exception {
		return v8ObjScript.executeDouble();
	}

	@Benchmark
	public Object nashorn_js_dynamic_obj_read() throws Exception {
		return nashornObjScript.eval(nashornBindings);
	}

	@Benchmark
	public Object graal_js_dynamic_obj_read() {
		return graalContext.eval(graalObjScript);
	}
	//endregion

	//region 6. 5-Shape 多态流水线 Poly (MagicJS vs V8 vs GraalJS vs Nashorn)
	@Benchmark
	public double magic_js_poly() throws Throwable {
		return magicPolyScript.runDouble(magicContext);
	}

	@Benchmark
	public double v8_js_poly() throws Exception {
		return v8PolyScript.executeDouble();
	}

	@Benchmark
	public Object nashorn_js_poly() throws Exception {
		return nashornPolyScript.eval(nashornBindings);
	}

	@Benchmark
	public Object graal_js_poly() {
		return graalContext.eval(graalPolyScript);
	}
	//endregion
}