package hope.magic.example;

import hope.magic.js.ast.Node;
import hope.magic.js.compiler.JSCompiler;
import hope.magic.js.parser.JSLexer;
import hope.magic.js.parser.JSParser;
import hope.magic.js.runtime.JSContext;
import hope.magic.js.runtime.JSScript;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class MagicJSBenchmark {

	private TargetObject target;

	// MagicJS compiled scripts
	private JSContext magicContext;
	private JSScript magicFieldScript;
	private JSScript magicMethodScript;
	private JSScript magicLoopScript;
	private JSScript magicObjScript;

	// Rhino compiled scripts
	private Context rhinoContext;
	private Scriptable rhinoScope;
	private Script rhinoFieldScript;
	private Script rhinoMethodScript;
	private Script rhinoLoopScript;
	private Script rhinoObjScript;

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

		target = MagicAccessorSample.newTargetObject(98765, "Benchmark Object");

		// 1. 初始化 MagicJS
		magicContext = new JSContext();
		magicContext.set("target", target);
		magicContext.eval("var dynamicObj = { x: 100, y: 200, name: 'MagicJS' };");

		magicFieldScript = compileMagicJS("target.secretCode;");
		magicMethodScript = compileMagicJS("target.multiply(6, 7);");
		magicLoopScript = compileMagicJS(code);
		magicObjScript = compileMagicJS("dynamicObj.x;");

		// 2. 初始化 Mozilla Rhino
		rhinoContext = Context.enter();
		rhinoContext.setOptimizationLevel(9); // JIT 编译级别 9
		rhinoScope = rhinoContext.initStandardObjects();
		Object wrappedTarget = Context.javaToJS(target, rhinoScope);
		org.mozilla.javascript.ScriptableObject.putProperty(rhinoScope, "target", wrappedTarget);
		rhinoContext.evaluateString(rhinoScope, "var dynamicObj = { x: 100, y: 200, name: 'MagicJS' };", "objInit", 1, null);

		rhinoFieldScript = rhinoContext.compileString("target.secretCode;", "fieldScript", 1, null);
		rhinoMethodScript = rhinoContext.compileString("target.multiply(6, 7);", "methodScript", 1, null);
		rhinoLoopScript = rhinoContext.compileString(code, "loopScript", 1, null);
		rhinoObjScript = rhinoContext.compileString("dynamicObj.x;", "objScript", 1, null);
	}

	@TearDown(Level.Trial)
	public void tearDown() {
		Context.exit();
	}

	private JSScript compileMagicJS(String code) throws Exception {
		JSLexer lexer = new JSLexer(code);
		JSParser parser = new JSParser(lexer.tokenize());
		Node.Program program = parser.parse();
		return JSCompiler.compile(program);
	}

	// ==================== 1. Java Direct 原生基准 ====================

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

	// ==================== 2. 字段访问对比 (MagicJS vs Rhino) ====================

	@Benchmark
	public Object magic_js_field_read() throws Throwable {
		return magicFieldScript.run(magicContext);
	}

	@Benchmark
	public Object rhino_js_field_read() {
		return rhinoFieldScript.exec(rhinoContext, rhinoScope);
	}

	// ==================== 3. 方法调用对比 (MagicJS vs Rhino) ====================

	@Benchmark
	public Object magic_js_method_call() throws Throwable {
		return magicMethodScript.run(magicContext);
	}

	@Benchmark
	public Object rhino_js_method_call() {
		return rhinoMethodScript.exec(rhinoContext, rhinoScope);
	}

	// ==================== 4. 1000以内质数和计算 (MagicJS vs Rhino) ====================

	@Benchmark
	public Object magic_js_prime_sum_1000() throws Throwable {
		return magicLoopScript.run(magicContext);
	}

	@Benchmark
	public Object rhino_js_prime_sum_1000() {
		return rhinoLoopScript.exec(rhinoContext, rhinoScope);
	}

	// ==================== 5. 动态 JSObject 属性访问 (Shape IC vs Rhino) ====================

	@Benchmark
	public Object magic_js_dynamic_obj_read() throws Throwable {
		return magicObjScript.run(magicContext);
	}

	@Benchmark
	public Object rhino_js_dynamic_obj_read() {
		return rhinoObjScript.exec(rhinoContext, rhinoScope);
	}
}
