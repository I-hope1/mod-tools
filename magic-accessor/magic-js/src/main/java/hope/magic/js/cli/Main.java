package hope.magic.js.cli;

import hope.magic.js.ast.Node;
import hope.magic.js.ast.Token;
import hope.magic.js.compiler.ConstantFolder;
import hope.magic.js.compiler.JSCompiler;
import hope.magic.js.parser.JSLexer;
import hope.magic.js.parser.JSParser;
import hope.magic.js.runtime.JSContext;
import hope.magic.js.runtime.JSObject;
import hope.magic.js.runtime.JSOps;
import hope.magic.js.runtime.JSScript;
import hope.magic.js.runtime.JSUndefined;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * MagicJS 官方命令行交互与执行入口 (CLI Entry Point)
 *
 * 用法:
 *   java -jar magic-js.jar <script.js>                 执行 JS 脚本文件
 *   java -jar magic-js.jar <f1.js> <f2.js> ...          顺序执行多个 JS 文件 (共享上下文)
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
		String inlineCode = null;
		List<String> scriptFiles = new ArrayList<>();
		int benchRuns = 1000;
		boolean readStdin = false;

		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			if (arg.equals("--bench") || arg.equals("-b")) {
				isBench = true;
				if (i + 1 < args.length && args[i + 1].matches("\\d+")) {
					try {
						benchRuns = Integer.parseInt(args[++i]);
					} catch (NumberFormatException ignored) {}
				}
			} else if (arg.equals("-e")) {
				if (i + 1 >= args.length) {
					System.err.println("错误: -e 需要提供代码字符串");
					System.exit(1);
				}
				inlineCode = args[++i];
			} else if (arg.equals("-")) {
				readStdin = true;
			} else if (arg.startsWith("-")) {
				// 忽略未知的引擎选项（如 --module, --harmony, --strict 等），确保兼容各类外部宿主 runner
			} else {
				scriptFiles.add(arg);
			}
		}

		if (isBench) {
			if (inlineCode != null) {
				runBenchmark("-e", inlineCode, benchRuns);
			} else if (!scriptFiles.isEmpty()) {
				String filePath = scriptFiles.get(0);
				try {
					String code = Files.readString(new File(filePath).toPath(), StandardCharsets.UTF_8);
					runBenchmark(filePath, code, benchRuns);
				} catch (Throwable e) {
					System.err.println("错误: 读取脚本文件失败: " + e.getMessage());
					System.exit(1);
				}
			} else {
				System.err.println("错误: --bench 需要提供脚本文件或 -e 表达式");
				System.exit(1);
			}
			return;
		}

		if (inlineCode != null) {
			runInlineCode(inlineCode);
			return;
		}

		if (readStdin) {
			runStdin();
			return;
		}

		if (!scriptFiles.isEmpty()) {
			runScriptFiles(scriptFiles);
			return;
		}

		runRepl();
	}

	private static void runScriptFiles(List<String> filePaths) {
		try {
			JSContext cx = new JSContext();
			for (String path : filePaths) {
				File f = new File(path);
				if (!f.exists() || !f.isFile()) {
					System.err.println("Error: Script file not found: " + path);
					System.exit(1);
				}
				String code = Files.readString(f.toPath(), StandardCharsets.UTF_8);
				JSScript script = JSCompiler.compile(code);
				script.run(cx);
			}
		} catch (Throwable e) {
			System.err.println(formatError(e));
			System.exit(1);
		}
	}

	private static void runInlineCode(String code) {
		try {
			JSContext cx = new JSContext();
			JSScript script = JSCompiler.compile(code);
			Object res = script.run(cx);
			if (res != null && res != JSUndefined.INSTANCE) {
				System.out.println(JSOps.toStr(res));
			}
		} catch (Throwable e) {
			System.err.println(formatError(e));
			System.exit(1);
		}
	}

	private static void runStdin() {
		try {
			String code = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
			JSContext cx = new JSContext();
			JSScript script = JSCompiler.compile(code);
			Object res = script.run(cx);
			if (res != null && res != JSUndefined.INSTANCE) {
				System.out.println(JSOps.toStr(res));
			}
		} catch (Throwable e) {
			System.err.println(formatError(e));
			System.exit(1);
		}
	}

	public static String formatError(Throwable t) {
		if (t == null) return "Error";
		if (t instanceof JSOps.JSException jse) {
			Object val = jse.value;
			if (val instanceof JSObject obj) {
				Object name = obj.get("name");
				Object msg = obj.get("message");
				String nameStr = (name != JSUndefined.INSTANCE && name != null) ? JSOps.toStr(name) : "Error";
				String msgStr = (msg != JSUndefined.INSTANCE && msg != null) ? JSOps.toStr(msg) : "";
				return msgStr.isEmpty() ? nameStr : nameStr + ": " + msgStr;
			}
			return JSOps.toStr(val);
		}
		String msg = t.getMessage();
		String simpleName = t.getClass().getSimpleName();
		if (msg == null || msg.isEmpty()) return simpleName;
		if (msg.startsWith("Test262Error") || msg.startsWith("TypeError") || msg.startsWith("SyntaxError")
				|| msg.startsWith("ReferenceError") || msg.startsWith("RangeError") || msg.startsWith("Error")) {
			return msg;
		}
		if (msg.startsWith("Unexpected") || msg.startsWith("Unterminated") || msg.startsWith("Expected")) {
			return "SyntaxError: " + msg;
		}
		if (t instanceof NullPointerException || t instanceof ClassCastException || t instanceof IllegalArgumentException) {
			return "TypeError: " + msg;
		}
		return simpleName + ": " + msg;
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
