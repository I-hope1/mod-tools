package hope.magic.example;

import hope.magic.js.ast.*;
import hope.magic.js.compiler.*;
import hope.magic.js.parser.*;
import hope.magic.js.runtime.*;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import java.util.*;

public class MagicJSColdStartBenchmark {

	public static class BenchmarkTarget {
		private int secretCode = 98765;
		public String name = "BenchmarkTarget";

		public int multiply(int a, int b) {
			return a * b;
		}

		public int computeBinary(java.util.function.IntBinaryOperator op, int a, int b) {
			return op.applyAsInt(a, b);
		}
	}

	public static void main(String[] args) throws Throwable {
		System.out.println("================================================================================");
		System.out.println("      🚀 MagicJS vs GraalJS vs Mozilla Rhino vs OpenJDK Nashorn 对比评测          ");
		System.out.println("================================================================================");
		System.out.println("JVM: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
		System.out.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
		System.out.println();

		BenchmarkTarget target = new BenchmarkTarget();
		List<Integer> sampleList = new ArrayList<>();
		for (int i = 1; i <= 100; i++) sampleList.add(i);

		// ==================== 1. 引擎初始化冷启动 (First Context Bootstrap) ====================
		System.out.println("--------------------------------------------------------------------------------");
		System.out.println("【1. 引擎初始化冷启动耗时 (First Engine Context Initialization)】");
		System.out.println("--------------------------------------------------------------------------------");

		// 1.1 MagicJS
		long tMagic0 = System.nanoTime();
		JSContext magicContext = new JSContext();
		long magicInitNs = System.nanoTime() - tMagic0;
		System.out.printf("  • MagicJS 首次 new JSContext() 时延:          %8.3f ms (%d ns)%n", magicInitNs / 1_000_000.0, magicInitNs);

		// 1.2 GraalJS (Polyglot Context)
		long tGraal0 = System.nanoTime();
		org.graalvm.polyglot.Context graalContext = org.graalvm.polyglot.Context.newBuilder("js")
			.allowAllAccess(true)
			.build();
		// 触发展开内部 Truffle 运行时
		graalContext.initialize("js");
		long graalInitNs = System.nanoTime() - tGraal0;
		System.out.printf("  • Oracle GraalJS Context.newBuilder().build():%8.3f ms (%d ns)%n", graalInitNs / 1_000_000.0, graalInitNs);

		// 1.3 Mozilla Rhino
		long tRhino0 = System.nanoTime();
		Context rhinoContext = Context.enter();
		rhinoContext.setOptimizationLevel(9); // JIT 编译级别 9
		Scriptable rhinoScope = rhinoContext.initStandardObjects();
		long rhinoInitNs = System.nanoTime() - tRhino0;
		System.out.printf("  • Mozilla Rhino Context.enter() + init:       %8.3f ms (%d ns)%n", rhinoInitNs / 1_000_000.0, rhinoInitNs);

		// 1.4 OpenJDK Nashorn
		long tNashorn0 = System.nanoTime();
		NashornScriptEngineFactory nashornFactory = new NashornScriptEngineFactory();
		ScriptEngine nashorn = nashornFactory.getScriptEngine();
		long nashornInitNs = System.nanoTime() - tNashorn0;
		System.out.printf("  • OpenJDK Nashorn 首次 getScriptEngine():     %8.3f ms (%d ns)%n", nashornInitNs / 1_000_000.0, nashornInitNs);

		System.out.printf("  ==> MagicJS 引擎初始化比 GraalJS 快:           %8.2fx%n", (double) graalInitNs / magicInitNs);
		System.out.printf("  ==> MagicJS 引擎初始化比 Rhino 快:             %8.2fx%n", (double) rhinoInitNs / magicInitNs);
		System.out.printf("  ==> MagicJS 引擎初始化比 Nashorn 快:           %8.2fx%n", (double) nashornInitNs / magicInitNs);
		System.out.println();

		// ==================== 2. 典型脚本首次编译各阶段微观耗时拆解 (Micro-Step Compilation Breakdown) ====================
		System.out.println("--------------------------------------------------------------------------------");
		System.out.println("【2. 典型脚本首次编译耗时对比 (Script Compilation Breakdown)】");
		System.out.println("--------------------------------------------------------------------------------");

		String sampleCode = """
			var sum = 0;
			for (var i = 2; i < 1000; i++) {
			    var isPrime = 1;
			    for (var j = 2; j * j <= i; j++) {
			        if (i % j === 0) isPrime = 0;
			    }
			    if (isPrime === 1) sum += i;
			}
			sum;
		""";

		// 2.1 MagicJS 细粒度拆解
		long stepT0 = System.nanoTime();
		JSLexer lexer = new JSLexer(sampleCode);
		List<Token> tokens = lexer.tokenize();
		long lexerNs = System.nanoTime() - stepT0;

		long stepT1 = System.nanoTime();
		JSParser parser = new JSParser(tokens);
		Node.Program program = parser.parse();
		long parserNs = System.nanoTime() - stepT1;

		long stepT2 = System.nanoTime();
		Node.Program folded = ConstantFolder.fold(program);
		long foldNs = System.nanoTime() - stepT2;

		long stepT3 = System.nanoTime();
		JSScript magicScript = JSCompiler.compile(folded);
		long compileLoadNs = System.nanoTime() - stepT3;

		long magicTotalCompileNs = lexerNs + parserNs + foldNs + compileLoadNs;

		System.out.printf("  • MagicJS 词法分析 (Tokenize):                %8.3f µs (%d ns)%n", lexerNs / 1_000.0, lexerNs);
		System.out.printf("  • MagicJS 语法解析 (AST Build):              %8.3f µs (%d ns)%n", parserNs / 1_000.0, parserNs);
		System.out.printf("  • MagicJS 常量折叠 (Constant Fold):           %8.3f µs (%d ns)%n", foldNs / 1_000.0, foldNs);
		System.out.printf("  • MagicJS ASM 字节码生成与 JVM 类装载:       %8.3f µs (%d ns)%n", compileLoadNs / 1_000.0, compileLoadNs);
		System.out.printf("  ==> MagicJS 端到端首次编译总耗时:             %8.3f ms (%d ns)%n", magicTotalCompileNs / 1_000_000.0, magicTotalCompileNs);

		// 2.2 GraalJS 首次解析 Source
		long graalCompT0 = System.nanoTime();
		Source graalSource = Source.newBuilder("js", sampleCode, "sample.js").build();
		long graalCompileNs = System.nanoTime() - graalCompT0;
		System.out.printf("  • Oracle GraalJS 首次 Source.newBuilder 耗时: %8.3f ms (%d ns)%n", graalCompileNs / 1_000_000.0, graalCompileNs);

		// 2.3 Rhino 首次编译
		long rhinoCompT0 = System.nanoTime();
		Script rhinoScript = rhinoContext.compileString(sampleCode, "sample", 1, null);
		long rhinoCompileNs = System.nanoTime() - rhinoCompT0;
		System.out.printf("  • Mozilla Rhino 首次 compileString 耗时:      %8.3f ms (%d ns)%n", rhinoCompileNs / 1_000_000.0, rhinoCompileNs);

		// 2.4 Nashorn 首次编译
		long nashornCompT0 = System.nanoTime();
		CompiledScript nashornScript = ((Compilable) nashorn).compile(sampleCode);
		long nashornCompileNs = System.nanoTime() - nashornCompT0;
		System.out.printf("  • OpenJDK Nashorn 首次 compile 耗时:          %8.3f ms (%d ns)%n", nashornCompileNs / 1_000_000.0, nashornCompileNs);

		System.out.println();

		// ==================== 3. 首次执行冷启动 (First Run with Indy Linkage) vs 预热运行 ====================
		System.out.println("--------------------------------------------------------------------------------");
		System.out.println("【3. 首次执行冷启动 (Cold Run) vs 预热执行 (Warm Runs)】");
		System.out.println("--------------------------------------------------------------------------------");

		// MagicJS First Run vs Warm
		long magicRun0 = System.nanoTime();
		Object magicRes = magicScript.run(magicContext);
		long magicColdRunNs = System.nanoTime() - magicRun0;

		long magicRun1 = System.nanoTime();
		magicScript.run(magicContext);
		long magicWarm2Ns = System.nanoTime() - magicRun1;

		long magicWarm100Avg = 0;
		for (int i = 0; i < 100; i++) {
			long t = System.nanoTime();
			magicScript.run(magicContext);
			magicWarm100Avg += (System.nanoTime() - t);
		}
		magicWarm100Avg /= 100;

		System.out.printf("  • [MagicJS] 首次冷执行时延 (首调含 Indy 链接): %8.3f ms (%d ns)  [结果: %s]%n", magicColdRunNs / 1_000_000.0, magicColdRunNs, magicRes);
		System.out.printf("  • [MagicJS] 第 2 次执行时延 (热):             %8.3f µs (%d ns)%n", magicWarm2Ns / 1_000.0, magicWarm2Ns);
		System.out.printf("  • [MagicJS] 第 100 次稳态单次执行平均耗时:    %8.3f µs (%d ns)%n", magicWarm100Avg / 1_000.0, magicWarm100Avg);
		System.out.println();

		// GraalJS First Run vs Warm
		long graalRun0 = System.nanoTime();
		Value graalRes = graalContext.eval(graalSource);
		long graalColdRunNs = System.nanoTime() - graalRun0;

		long graalRun1 = System.nanoTime();
		graalContext.eval(graalSource);
		long graalWarm2Ns = System.nanoTime() - graalRun1;

		long graalWarm100Avg = 0;
		for (int i = 0; i < 100; i++) {
			long t = System.nanoTime();
			graalContext.eval(graalSource);
			graalWarm100Avg += (System.nanoTime() - t);
		}
		graalWarm100Avg /= 100;

		System.out.printf("  • [GraalJS] 首次冷执行时延 (含 Truffle 解析): %8.3f ms (%d ns)  [结果: %s]%n", graalColdRunNs / 1_000_000.0, graalColdRunNs, graalRes);
		System.out.printf("  • [GraalJS] 第 2 次执行时延 (解释/早期编译):   %8.3f µs (%d ns)%n", graalWarm2Ns / 1_000.0, graalWarm2Ns);
		System.out.printf("  • [GraalJS] 第 100 次稳态单次执行平均耗时:    %8.3f µs (%d ns)%n", graalWarm100Avg / 1_000.0, graalWarm100Avg);
		System.out.println();

		// Rhino First Run vs Warm
		long rhinoRun0 = System.nanoTime();
		Object rhinoRes = rhinoScript.exec(rhinoContext, rhinoScope);
		long rhinoColdRunNs = System.nanoTime() - rhinoRun0;

		long rhinoRun1 = System.nanoTime();
		rhinoScript.exec(rhinoContext, rhinoScope);
		long rhinoWarm2Ns = System.nanoTime() - rhinoRun1;

		long rhinoWarm100Avg = 0;
		for (int i = 0; i < 100; i++) {
			long t = System.nanoTime();
			rhinoScript.exec(rhinoContext, rhinoScope);
			rhinoWarm100Avg += (System.nanoTime() - t);
		}
		rhinoWarm100Avg /= 100;

		System.out.printf("  • [Rhino] 首次冷执行时延:                     %8.3f ms (%d ns)  [结果: %s]%n", rhinoColdRunNs / 1_000_000.0, rhinoColdRunNs, rhinoRes);
		System.out.printf("  • [Rhino] 第 2 次执行时延 (热):               %8.3f µs (%d ns)%n", rhinoWarm2Ns / 1_000.0, rhinoWarm2Ns);
		System.out.printf("  • [Rhino] 第 100 次稳态单次执行平均耗时:      %8.3f µs (%d ns)%n", rhinoWarm100Avg / 1_000.0, rhinoWarm100Avg);
		System.out.println();

		// Nashorn First Run vs Warm
		Bindings nashornBindings = nashorn.createBindings();
		long nashornRun0 = System.nanoTime();
		Object nashornRes = nashornScript.eval(nashornBindings);
		long nashornColdRunNs = System.nanoTime() - nashornRun0;

		long nashornRun1 = System.nanoTime();
		nashornScript.eval(nashornBindings);
		long nashornWarm2Ns = System.nanoTime() - nashornRun1;

		long nashornWarm100Avg = 0;
		for (int i = 0; i < 100; i++) {
			long t = System.nanoTime();
			nashornScript.eval(nashornBindings);
			nashornWarm100Avg += (System.nanoTime() - t);
		}
		nashornWarm100Avg /= 100;

		System.out.printf("  • [Nashorn] 首次冷执行时延 (首调含 Dynalink):  %8.3f ms (%d ns)  [结果: %s]%n", nashornColdRunNs / 1_000_000.0, nashornColdRunNs, nashornRes);
		System.out.printf("  • [Nashorn] 第 2 次执行时延 (热):             %8.3f µs (%d ns)%n", nashornWarm2Ns / 1_000.0, nashornWarm2Ns);
		System.out.printf("  • [Nashorn] 第 100 次稳态单次执行平均耗时:    %8.3f µs (%d ns)%n", nashornWarm100Avg / 1_000.0, nashornWarm100Avg);
		System.out.println();

		// ==================== 4. 典型多场景端到端冷启动耗时对比 ====================
		System.out.println("--------------------------------------------------------------------------------");
		System.out.println("【4. 五大业务场景端到端冷启动耗时对比 (MagicJS vs GraalJS vs Rhino vs Nashorn)】");
		System.out.println("--------------------------------------------------------------------------------");

		magicContext.set("target", target);
		magicContext.set("list", sampleList);

		graalContext.getBindings("js").putMember("target", target);
		graalContext.getBindings("js").putMember("list", sampleList);

		ScriptableObject.putProperty(rhinoScope, "target", Context.javaToJS(target, rhinoScope));
		ScriptableObject.putProperty(rhinoScope, "list", Context.javaToJS(sampleList, rhinoScope));

		nashornBindings.put("target", target);
		nashornBindings.put("list", sampleList);

		compareScenario("场景 A: 复杂循环与素数筛选 (Prime Sum 1000)", sampleCode, magicContext, graalContext, rhinoContext, rhinoScope, nashorn, nashornBindings);
		compareScenario("场景 B: Java 反射与实例方法直调 (target.multiply(6, 7))", "target.multiply(6, 7);", magicContext, graalContext, rhinoContext, rhinoScope, nashorn, nashornBindings);
		compareScenario("场景 C: 动态 JS 对象字面量与属性访问 ({ x: 10, y: 20 }.x)", "var o = { x: 100, y: 200, name: 'MagicJS' }; o.x + o.y;", magicContext, graalContext, rhinoContext, rhinoScope, nashorn, nashornBindings);
		compareScenario("场景 D: 箭头/匿名函数与 Java 函数式接口 SAM 适配", "target.computeBinary(function(x, y) { return x * y + 5; }, 4, 5);", magicContext, graalContext, rhinoContext, rhinoScope, nashorn, nashornBindings);
		compareScenario("场景 E: 循环遍历 Java 集合 (Java List for-loop)", "var total = 0; for (var i = 0; i < list.size(); i++) { total += list.get(i); } total;", magicContext, graalContext, rhinoContext, rhinoScope, nashorn, nashornBindings);

		String polyPipelineCode = """
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
		compareScenario("场景 F: 动态多态数据流与高频类型变化 (Polymorphic Pipeline 5-Shapes + Mixed Types)", polyPipelineCode, magicContext, graalContext, rhinoContext, rhinoScope, nashorn, nashornBindings);

		System.out.println("================================================================================");
		System.out.println("                          四大引擎对比基准测试执行完毕                             ");
		System.out.println("================================================================================");
		Context.exit();
		graalContext.close();
	}

	private static void compareScenario(
		String scenarioName,
		String code,
		JSContext magicContext,
		org.graalvm.polyglot.Context graalContext,
		Context rhinoContext,
		Scriptable rhinoScope,
		ScriptEngine nashorn,
		Bindings nashornBindings
	) throws Throwable {
		System.out.printf("👉 %s%n", scenarioName);

		// 1. MagicJS
		long t0 = System.nanoTime();
		JSScript mScript = JSCompiler.compile(code);
		long mCompNs = System.nanoTime() - t0;
		long t1 = System.nanoTime();
		Object mRes = mScript.run(magicContext);
		long mRun1Ns = System.nanoTime() - t1;
		long t2 = System.nanoTime();
		mScript.run(magicContext);
		long mRun2Ns = System.nanoTime() - t2;

		// 2. GraalJS
		long g0 = System.nanoTime();
		Source gSource = Source.newBuilder("js", code, "sc.js").build();
		long gCompNs = System.nanoTime() - g0;
		long g1 = System.nanoTime();
		Value gRes = graalContext.eval(gSource);
		long gRun1Ns = System.nanoTime() - g1;
		long g2 = System.nanoTime();
		graalContext.eval(gSource);
		long gRun2Ns = System.nanoTime() - g2;

		// 3. Rhino
		long r0 = System.nanoTime();
		Script rScript = rhinoContext.compileString(code, "sc", 1, null);
		long rCompNs = System.nanoTime() - r0;
		long r1 = System.nanoTime();
		Object rRes = rScript.exec(rhinoContext, rhinoScope);
		long rRun1Ns = System.nanoTime() - r1;
		long r2 = System.nanoTime();
		rScript.exec(rhinoContext, rhinoScope);
		long rRun2Ns = System.nanoTime() - r2;

		// 4. Nashorn
		long n0 = System.nanoTime();
		CompiledScript nScript = ((Compilable) nashorn).compile(code);
		long nCompNs = System.nanoTime() - n0;
		long n1 = System.nanoTime();
		Object nRes = nScript.eval(nashornBindings);
		long nRun1Ns = System.nanoTime() - n1;
		long n2 = System.nanoTime();
		nScript.eval(nashornBindings);
		long nRun2Ns = System.nanoTime() - n2;

		System.out.printf("   [MagicJS]  首次编译: %7.3f ms | 首次执行: %7.3f ms | 端到端全冷: %7.3f ms | 第2次热调: %7.3f µs%n",
			mCompNs / 1_000_000.0, mRun1Ns / 1_000_000.0, (mCompNs + mRun1Ns) / 1_000_000.0, mRun2Ns / 1_000.0);
		System.out.printf("   [GraalJS]  首次解析: %7.3f ms | 首次执行: %7.3f ms | 端到端全冷: %7.3f ms | 第2次热调: %7.3f µs%n",
			gCompNs / 1_000_000.0, gRun1Ns / 1_000_000.0, (gCompNs + gRun1Ns) / 1_000_000.0, gRun2Ns / 1_000.0);
		System.out.printf("   [Rhino]    首次编译: %7.3f ms | 首次执行: %7.3f ms | 端到端全冷: %7.3f ms | 第2次热调: %7.3f µs%n",
			rCompNs / 1_000_000.0, rRun1Ns / 1_000_000.0, (rCompNs + rRun1Ns) / 1_000_000.0, rRun2Ns / 1_000.0);
		System.out.printf("   [Nashorn]  首次编译: %7.3f ms | 首次执行: %7.3f ms | 端到端全冷: %7.3f ms | 第2次热调: %7.3f µs%n",
			nCompNs / 1_000_000.0, nRun1Ns / 1_000_000.0, (nCompNs + nRun1Ns) / 1_000_000.0, nRun2Ns / 1_000.0);
		System.out.println();
	}
}
