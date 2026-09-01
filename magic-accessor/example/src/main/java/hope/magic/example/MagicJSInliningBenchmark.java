package hope.magic.example;

import hope.magic.js.compiler.JSCompiler;
import hope.magic.js.runtime.JSContext;
import hope.magic.js.runtime.JSScript;

import java.util.ArrayList;
import java.util.List;

public class MagicJSInliningBenchmark {

	public static class BenchmarkTarget {
		public int secretCode = 98765;

		public int multiply(int a, int b) {
			return a * b;
		}

		public int computeBinary(java.util.function.IntBinaryOperator op, int a, int b) {
			return op.applyAsInt(a, b);
		}
	}

	public static void main(String[] args) throws Throwable {
		System.out.println("================================================================================");
		System.out.println("       🔥 MagicJS HotSpot C2 JIT Inlining Analysis (-XX:+PrintInlining)         ");
		System.out.println("================================================================================");
		System.out.println("JVM: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
		System.out.println();

		BenchmarkTarget target = new BenchmarkTarget();
		List<Integer> list = new ArrayList<>();
		for (int i = 1; i <= 100; i++) list.add(i);

		JSContext cx = new JSContext();
		cx.set("target", target);
		cx.set("list", list);

		// 1. 密集算术与循环脚本
		System.out.println(">>> [1/5] 编译并预热算术密集型脚本 (Prime Sum 1000)...");
		JSScript primeScript = JSCompiler.compile("""
			var sum = 0;
			for (var i = 2; i < 1000; i++) {
			    var isPrime = 1;
			    for (var j = 2; j * j <= i; j++) {
			        if (i % j === 0) isPrime = 0;
			    }
			    if (isPrime === 1) sum += i;
			}
			sum;
		""");
		for (int i = 0; i < 200_000; i++) {
			primeScript.run(cx);
		}

		// 2. Java 实例方法直调
		System.out.println(">>> [2/5] 编译并预热 Java 实例方法直调 (target.multiply(6, 7))...");
		JSScript methodScript = JSCompiler.compile("target.multiply(6, 7);");
		for (int i = 0; i < 200_000; i++) {
			methodScript.run(cx);
		}

		// 3. 箭头函数与 SAM 接口适配
		System.out.println(">>> [3/5] 编译并预热 SAM 接口转换 (target.computeBinary((x, y) => x * y + 5, 4, 5))...");
		JSScript samScript = JSCompiler.compile("target.computeBinary((x, y) => x * y + 5, 4, 5);");
		for (int i = 0; i < 200_000; i++) {
			samScript.run(cx);
		}

		// 4. for..of 循环遍历 Java 集合
		System.out.println(">>> [4/5] 编译并预热 for..of Java List 遍历...");
		JSScript forOfScript = JSCompiler.compile("""
			var total = 0;
			for (var x of list) {
			    total += x;
			}
			total;
		""");
		for (int i = 0; i < 200_000; i++) {
			forOfScript.run(cx);
		}

		// 5. 正则表达式测试与匹配
		System.out.println(">>> [5/6] 编译并预热 RegExp 匹配与执行...");
		JSScript regexScript = JSCompiler.compile("""
			var r = /item(\\d+)/;
			var match = r.exec("item12345");
			match[1];
		""");
		for (int i = 0; i < 200_000; i++) {
			regexScript.run(cx);
		}

		// 6. 复杂多态数据流与高频变型流水线 (Polymorphic Pipeline 5-Shapes + Mixed Types)
		System.out.println(">>> [6/6] 编译并预热多态数据流与高频类型变化流水线 (5 形状多态 + 裸浮点槽直读)...");
		JSScript polyScript = JSCompiler.compile("""
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
		""");
		for (int i = 0; i < 50_000; i++) {
			polyScript.run(cx);
		}

		System.out.println(">>> 预热完成，所有热点脚本（包括多态流水线）已深度达到 C2 JIT 稳态！");
	}
}
