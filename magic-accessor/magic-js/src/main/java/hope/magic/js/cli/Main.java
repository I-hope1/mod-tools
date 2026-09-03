package hope.magic.js.cli;

import hope.magic.js.ast.Node;
import hope.magic.js.ast.Token;
import hope.magic.js.compiler.ConstantFolder;
import hope.magic.js.compiler.JSCompiler;
import hope.magic.js.parser.JSLexer;
import hope.magic.js.parser.JSParser;
import hope.magic.js.runtime.JSContext;
import hope.magic.js.runtime.JSScript;
import hope.magic.js.runtime.JSUndefined;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * MagicJS 官方命令行交互与执行入口 (CLI Entry Point)
 *
 * 用法:
 *   java -jar magic-js.jar <script.js>                 执行 JS 脚本文件
 *   java -jar magic-js.jar -e "console.log(1+2);"       直接执行单行表达式
 *   java -jar magic-js.jar --bench <script.js> [runs]   基准测试模式 (微秒级冷热时延拆解)
 *   java -jar magic-js.jar --bench -e "<code>" [runs]   基准测试模式 (行内代码)
 *   java -jar magic-js.jar                              交互式 REPL 终端
 */
public class Main {

	public static final String VERSION = "1.6.0";

	public static void main(String[] args) {
		if (args.length == 0) {
			runRepl();
			return;
		}

		String first = args[0];
		if (first.equals("-v") || first.equals("--version")) {
			System.out.println("MagicJS v" + VERSION + " (High-Performance JS Engine on HotSpot/GraalVM)");
			return;
		}

		if (first.equals("-h") || first.equals("--help")) {
			printHelp();
			return;
		}

		boolean isBench = false;
		int argIdx = 0;
		if (first.equals("--bench") || first.equals("-b")) {
			isBench = true;
			argIdx++;
			if (argIdx >= args.length) {
				System.err.println("错误: --bench 需要提供脚本文件或 -e 表达式");
				System.exit(1);
			}
		}

		String code = null;
		String sourceName = "<stdin>";

		if (args[argIdx].equals("-e")) {
			if (argIdx + 1 >= args.length) {
				System.err.println("错误: -e 需要提供代码字符串");
				System.exit(1);
			}
			code = args[argIdx + 1];
			sourceName = "-e";
			argIdx += 2;
		} else {
			String filePath = args[argIdx];
			File f = new File(filePath);
			if (!f.exists() || !f.isFile()) {
				System.err.println("错误: 找不到脚本文件: " + filePath);
				System.exit(1);
			}
			try {
				code = Files.readString(f.toPath(), StandardCharsets.UTF_8);
				sourceName = f.getName();
				argIdx++;
			} catch (Throwable e) {
				System.err.println("错误: 读取文件失败: " + e.getMessage());
				System.exit(1);
			}
		}

		int benchRuns = 1000;
		if (isBench && argIdx < args.length) {
			try {
				benchRuns = Integer.parseInt(args[argIdx]);
			} catch (NumberFormatException ignored) {}
		}

		if (isBench) {
			runBenchmark(sourceName, code, benchRuns);
		} else {
			runScript(sourceName, code);
		}
	}

	private static void runScript(String sourceName, String code) {
		try {
			JSContext cx = new JSContext();
			JSScript script = JSCompiler.compile(code);
			Object res = script.run(cx);
			if (res != null && res != JSUndefined.INSTANCE) {
				System.out.println(res);
			}
		} catch (Throwable e) {
			System.err.println("运行时异常 [" + sourceName + "]: " + e.getMessage());
			e.printStackTrace();
			System.exit(1);
		}
	}

	private static void runBenchmark(String sourceName, String code, int runs) {
		System.out.println("================================================================================");
		System.out.println("  📊 MagicJS 性能微观剖析与冷热启动基准测算: " + sourceName);
		System.out.println("================================================================================");

		try {
			// 1. 词法分析
			long t0 = System.nanoTime();
			JSLexer lexer = new JSLexer(code);
			List<Token> tokens = lexer.tokenize();
			long lexerNs = System.nanoTime() - t0;

			// 2. 语法解析
			long t1 = System.nanoTime();
			JSParser parser = new JSParser(tokens);
			Node.Program prog = parser.parse();
			long parseNs = System.nanoTime() - t1;

			// 3. 常量折叠
			long t2 = System.nanoTime();
			Node.Program folded = ConstantFolder.fold(prog);
			long foldNs = System.nanoTime() - t2;

			// 4. ASM 编译生成与动态装载
			long t3 = System.nanoTime();
			JSScript script = JSCompiler.compile(folded);
			long compileNs = System.nanoTime() - t3;

			long totalCompileNs = lexerNs + parseNs + foldNs + compileNs;

			// 5. 引擎上下文初始化
			long t4 = System.nanoTime();
			JSContext cx = new JSContext();
			long cxInitNs = System.nanoTime() - t4;

			// 6. 首次冷执行 (包含 invokedynamic 首次链接与 Shape 迁移)
			long t5 = System.nanoTime();
			Object firstRes = script.run(cx);
			long firstRunNs = System.nanoTime() - t5;

			// 7. 第二次预热执行
			long t6 = System.nanoTime();
			script.run(cx);
			long secondRunNs = System.nanoTime() - t6;

			// 8. 稳态压测
			long warmTotalNs = 0;
			long minNs = Long.MAX_VALUE;
			long maxNs = Long.MIN_VALUE;

			for (int i = 0; i < runs; i++) {
				long st = System.nanoTime();
				script.run(cx);
				long el = System.nanoTime() - st;
				warmTotalNs += el;
				if (el < minNs) minNs = el;
				if (el > maxNs) maxNs = el;
			}
			double avgNs = (double) warmTotalNs / runs;

			System.out.printf("  • 词法解析 (Tokenize):            %8.3f µs  (%d ns)%n", lexerNs / 1_000.0, lexerNs);
			System.out.printf("  • AST 语法解析 (Parse):           %8.3f µs  (%d ns)%n", parseNs / 1_000.0, parseNs);
			System.out.printf("  • 常量折叠优化 (Fold):             %8.3f µs  (%d ns)%n", foldNs / 1_000.0, foldNs);
			System.out.printf("  • ASM 字节码生成与 JVM 类装载:    %8.3f µs  (%d ns)%n", compileNs / 1_000.0, compileNs);
			System.out.println("  ------------------------------------------------------------------------------");
			System.out.printf("  ⚡ 首次编译总时延 (Compile Time):   %8.3f ms  (%d ns)%n", totalCompileNs / 1_000_000.0, totalCompileNs);
			System.out.printf("  ⚡ 上下文创建时延 (Context Init):  %8.3f µs  (%d ns)%n", cxInitNs / 1_000.0, cxInitNs);
			System.out.printf("  ⚡ 首次执行冷启动 (First Cold Run): %8.3f ms  (%d ns)%n", firstRunNs / 1_000_000.0, firstRunNs);
			System.out.printf("  ⚡ 第 2 次执行预热 (Warmup Run 2): %8.3f µs  (%d ns)%n", secondRunNs / 1_000.0, secondRunNs);
			System.out.printf("  🚀 稳态单次执行均值 (%d 轮):      %8.3f µs  [最小: %.2f µs, 最大: %.2f µs]%n",
				runs, avgNs / 1_000.0, minNs / 1_000.0, maxNs / 1_000.0);
			System.out.println("  ------------------------------------------------------------------------------");
			System.out.println("  执行结果: " + firstRes);
			System.out.println("================================================================================");
		} catch (Throwable e) {
			System.err.println("基准测试失败: " + e.getMessage());
			e.printStackTrace();
		}
	}

	private static void runRepl() {
		System.out.println("MagicJS Interactive REPL (v" + VERSION + ")");
		System.out.println("Type 'exit' or Ctrl+C to quit.\n");

		JSContext cx = new JSContext();
		BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

		while (true) {
			try {
				System.out.print("> ");
				String line = reader.readLine();
				if (line == null || line.trim().equals("exit")) {
					break;
				}
				line = line.trim();
				if (line.isEmpty()) continue;

				Object res = cx.eval(line);
				if (res != null && res != JSUndefined.INSTANCE) {
					System.out.println(res);
				}
			} catch (Throwable e) {
				System.out.println(e.getClass().getSimpleName() + ": " + e.getMessage());
			}
		}
	}

	private static void printHelp() {
		System.out.println("MagicJS - 超高性能轻量级 Java 嵌入式 JavaScript 引擎");
		System.out.println();
		System.out.println("用法:");
		System.out.println("  magicjs [选项] <脚本路径.js> [参数...]");
		System.out.println("  magicjs -e <代码表达式>");
		System.out.println("  magicjs --bench <脚本路径.js | -e \"代码\"> [压测轮数]");
		System.out.println();
		System.out.println("选项:");
		System.out.println("  -e <代码>          直接在命令行执行 JS 代码");
		System.out.println("  -b, --bench        进入高精度基准测试模式，统计冷启动与热执行纳秒耗时");
		System.out.println("  -v, --version      显示 MagicJS 版本");
		System.out.println("  -h, --help         显示帮助信息");
	}
}
