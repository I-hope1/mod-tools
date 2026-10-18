package modtools.annotations.unsafe;

import com.sun.source.util.*;
import com.sun.tools.javac.api.*;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.comp.*;
import com.sun.tools.javac.comp.CompileStates.CompileState;
import com.sun.tools.javac.comp.Resolve.RecoveryLoadClass;
import com.sun.tools.javac.jvm.*;
import com.sun.tools.javac.main.*;
import com.sun.tools.javac.model.JavacElements;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.Context.Key;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticFlag;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Log.DeferredDiagnosticHandler;
import modtools.annotations.*;
import modtools.annotations.PrintHelper.SPrinter;
import modtools.annotations.processors.ContentProcessor;
import modtools.annotations.unsafe.TopTranslator.CheckException;

import javax.annotation.processing.*;
import javax.lang.model.element.Element;
import javax.tools.JavaFileObject;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.*;
import java.util.regex.*;

import static com.sun.tools.javac.code.Kinds.Kind.ERR;
import static com.sun.tools.javac.util.Iterators.createCompoundIterator;
import static modtools.annotations.HopeReflect.*;
import static modtools.annotations.PrintHelper.SPrinter.*;
import static modtools.annotations.PrintHelper.errs;
import static modtools.annotations.unsafe.ContextPatcher.removeKey;

@SuppressWarnings("removal")
public class Replace {
	public static Context       context;
	public static ClassFinder   classFinder;
	public static Symtab        syms;
	public static Enter         enter;
	public static JavacTrees    trees;
	public static Names         ns;
	public static TreeMaker     maker;
	public static JavacElements elements;
	public static ModuleFinder  moduleFinder;
	public static JavacMessages messages;
	public static Analyzer      analyzer;
	public static Filer         filer;

	public static JavaCompiler compiler;
	public static Log          log;

	static DefaultToStatic       defaultToStatic;
	// static DesugarStringTemplate desugarStringTemplate;
	static DesugarRecord         desugarRecord;
	static Properties            bundles = new Properties();

	// 增加一个标记，防止重复初始化
	private static final Key<Boolean> MODTOOLS_INIT_MARK = new Key<>();
	public static void extendingFunc(Context context) {
		/* 恢复初始状态 */
		unsafe.putInt(CompileState.INIT, off_stateValue, 0);
		if (context.get(MODTOOLS_INIT_MARK) != null) {
			// 已经在这个 Context 初始化过了，直接返回
			return;
		}
		context.put(MODTOOLS_INIT_MARK, true);

		Replace.context = context;
		classFinder = ClassFinder.instance(context);
		syms = Symtab.instance(context);
		enter = Enter.instance(context);
		trees = JavacTrees.instance(context);
		ns = Names.instance(context);
		maker = TreeMaker.instance(context);
		elements = JavacElements.instance(context);
		moduleFinder = ModuleFinder.instance(context);
		messages = JavacMessages.instance(context);
		analyzer = Analyzer.instance(context);
		log = Log.instance(context);
		compiler = JavaCompiler.instance(context);

		defaultToStatic = new DefaultToStatic(context);
		// desugarStringTemplate = new DesugarStringTemplate(context);
		desugarRecord = new DesugarRecord();

		messages.add(locale -> new ListResourceBundle() {
			protected Object[][] getContents() {
				return bundles.entrySet().stream().map(e -> new Object[]{e.getKey(), e.getValue()}).toArray(Object[][]::new);
			}
		});
		bundles.put("any.1", "{0}");
		bundles.put("any.err.1", "{0}");
		bundles.put("any.warn.1", "{0}");
		bundles.put("any.info.1", "{0}");
		try {
			extendingFunc0();
		} catch (Throwable e) { err(e); }
	}
	public static void extendingFunc(ProcessingEnvironment processingEnv) {
		filer = processingEnv.getFiler();
		extendingFunc(ProcessingEnvContext.of(processingEnv));
	}

	public static final HashMap<ModuleSymbol, ClassSymbol> moduleRepresentClass = new HashMap<>();
	/** 换 {@link Context} 时必须丢弃上一轮编译的模块/包符号 */
	public static void resetContextCaches() {
		moduleRepresentClass.clear();
		needExportedApi.clear();
		first = true;
	}
	public static void searchModuleExport(ModuleSymbol module) {
		if (moduleRepresentClass.containsKey(module)) return;
		module.exports.stream().filter(export -> export.modules == null).findFirst().ifPresent(d -> {
			if (d.packge.members_field == null) return;
			moduleRepresentClass.put(module, (ClassSymbol) d.packge.members_field.getSymbols(s -> s instanceof ClassSymbol).iterator().next());
		});
	}

	private static void extendingFunc0() throws Exception {
		accessOverride();

		JavacTargeting.forceJavaVersion(context);

		JavacTargeting.forcePreview(context);

		other();

		try {
			ContentProcessor.registerTodos(context);
		} catch (NoClassDefFoundError | NoSuchFieldError _) { }
	}

	static Symbol NOT_FOUND;
	/**
	 * 包括{@code Module}访问
	 * @see Resolve#doRecoveryLoadClass
	 */
	private static void accessOverride() {
		Resolve prev = Resolve.instance(context);
		removeKey(context, Resolve.class, () -> tryDefineOne(prev));
		Resolve resolve = Resolve.instance(context);
		NOT_FOUND = getAccess(Resolve.class, resolve, "typeNotFound");

		setRecovery(resolve);

		setAccess(Check.class, Check.instance(context), "rs", resolve);
		setAccess(Attr.class, Attr.instance(context), "rs", resolve);
	}
	/** @see Resolve#lookupInvisibleSymbol(Env, Name, Function, BiFunction, Predicate, Symbol) */
	private static void setRecovery(Resolve resolve) {
		setAccess(Resolve.class, resolve, "doRecoveryLoadClass", (RecoveryLoadClass) (env, name) -> {
			List<Name> candidates = Convert.classCandidates(name);
			return lookupInvisibleSymbol(env, name,
			 n -> () -> createCompoundIterator(candidates,
				c -> syms.getClassesForName(c)
				 .iterator()),
			 (ms, n) -> {
				 for (Name candidate : candidates) {
					 try {
						 return classFinder.loadClass(ms, candidate);
					 } catch (CompletionFailure cf) {
						 //ignore
					 }
				 }
				 return null;
			 }, sym -> sym.kind == Kind.TYP, NOT_FOUND);
		});
		setAccess(Resolve.class, resolve, "namedImportScopeRecovery", (RecoveryLoadClass) (env, name) -> {
			Scope importScope = env.toplevel.namedImportScope;
			return importScope.findFirst(Convert.shortName(name),
			 sym -> sym.kind == Kind.TYP && sym.flatName() == name);
		});
		setAccess(Resolve.class, resolve, "starImportScopeRecovery", (RecoveryLoadClass) (env, name) -> {
			Scope importScope = env.toplevel.starImportScope;
			Symbol existing = importScope.findFirst(Convert.shortName(name),
			 sym -> sym.kind == Kind.TYP && sym.flatName() == name);
			if (existing != null) {
				try {
					existing = classFinder.loadClass(existing.packge().modle, name);

					if (existing.exists()) return existing;
				} catch (CompletionFailure cf) {
					//ignore
				}
			}
			return null;
		});
		/* setAccess(Resolve.class, resolve, "accessibilityChecker", new SimpleVisitor<>() {
			public Object visitType(Type t, Object o) { return t; }
		}); */
	}
	private static Resolve tryDefineOne(Resolve resolve) {
		boolean hasAnnotation = hasAnnotation();
		var     predicate     = (BiPredicate<Env<AttrContext>, Symbol>) (env, __) -> hasAnnotation && env.enclClass.sym.getAnnotation(NoAccessCheck.class) != null;

		try {
			return new MyResolve(context, predicate);
		} catch (Exception ignored) { }

		return resolve;
	}
	private static boolean hasAnnotation() {
		try {
			NoAccessCheck.class.getClass();
		} catch (NoClassDefFoundError e) { return false; }
		return true;
	}
	private static void other() {
		// removeKey(MemberEnter.class, () -> new MyMemberEnter(context));

		fixSyntaxErrorAndSkipInvisibleError();

		// 适配d8无法编译jdk21的枚举(enum)的字节码
		Options.instance(context).put(Option.PARAMETERS, "");

		removeKey(context, TransPatterns.class, () -> new MyTransPatterns(context));

		TopTranslator     topTranslator     = TopTranslator.instance(context);
		MultiTaskListener multiTaskListener = MultiTaskListener.instance(context);
		multiTaskListener.getTaskListeners().removeIf(x -> x instanceof MyTaskListener);
		multiTaskListener.add(new MyTaskListener(topTranslator));
		// removeKey(TransTypes.class, () -> new MyTransTypes(context));
		// setAccess(JavaCompiler.class, JavaCompiler.instance(context), "transTypes", TransTypes.instance(context));
		// removeKey(Lower.class, () -> new Desugar(context));
		// Lower lower = Lower.instance(context);
		// setAccess(JavaCompiler.class, JavaCompiler.instance(context), "lower", lower);
		// setAccess(Gen.class, Gen.instance(context), "lower", lower);
	}
	public static final HashSet<PackageSymbol> needExportedApi = new HashSet<>();

	/**
	 * 摘掉两类诊断：包不可见错误，以及“能自愈”的语法错误（自愈逻辑见 {@link SyntaxErrorPatcher}）。
	 * <p>
	 * 自愈失败时保留诊断，让 javac 正常报错——这里不能反过来打断编译。
	 */
	public static void fixSyntaxErrorAndSkipInvisibleError() {
		DeferredDiagnosticHandler handler = getAccess(Log.class, Log.instance(context), "diagnosticHandler");
		Collection<JCDiagnostic> deferred = getAccess(DeferredDiagnosticHandler.class, handler, "deferred");
		ListBuffer<JCDiagnostic> buffer = new ListBuffer<>();

		SyntaxErrorPatcher patcher = SyntaxErrorPatcher.forContext(context);
		buffer.addAll(deferred.stream().filter(diag -> keepDiagnostic(diag, patcher)).toList());

		setAccess(DeferredDiagnosticHandler.class, handler, "deferred",
		 deferred instanceof java.util.List<JCDiagnostic> ? new ArrayList<>(buffer) : buffer);
	}

	/** @return {@code true} 表示保留该诊断 */
	private static boolean keepDiagnostic(JCDiagnostic diag, SyntaxErrorPatcher patcher) {
		// skip包不可见的错误
		if (diag.isFlagSet(DiagnosticFlag.RESOLVE_ERROR) && diag.getCode().contains("package.not.visible")) return false;
		// 只有语法错误才尝试自愈
		if (!diag.isFlagSet(DiagnosticFlag.SYNTAX)) return true;

		try {
			JavaFileObject source = diag.getSource();
			if (source == null) return true;

			String[] args = Arrays.stream(diag.getArgs()).map(String::valueOf).toArray(String[]::new);
			if (args.length == 0) {
				// illegal.start.of：多写了一个字符
				return !(diag.getCode().contains("illegal.start.of") && patcher.drop(source, (int) diag.getPosition()));
			}
			// 参数形如 "'x'"，即 javac 认为这里少了一个字符
			if (!(args[0].length() == 3 && args[0].charAt(0) == '\'' && args[0].charAt(2) == '\'')) return true;

			errs("Added " + args[0] + " at " + source.getName() + "(" + diag.getLineNumber() + ":" + diag.getColumnNumber() + ")");
			return !patcher.fix(source, (int) diag.getPosition(), args[0].charAt(1));
		} catch (Throwable e) {
			// 自动修正只是尽力而为：单条诊断失败就放过，绝不能让 javac 停下来
			errs("[MISC]: failed to patch source, skipped: " + e);
			return true;
		}
	}

	/** @see com.sun.tools.javac.comp.Resolve#lookupInvisibleSymbol */
	public static <S extends Symbol> Symbol lookupInvisibleSymbol(
	 Env<AttrContext> env,
	 Name name,
	 Function<Name, Iterable<S>> get,
	 BiFunction<ModuleSymbol, Name, S> load,
	 Predicate<S> validate,
	 Symbol defaultResult) {
		if (name.toString().startsWith("[")) return defaultResult;
		// 注意：这里曾经调用过 Times.mark()，但对应的 printElapsed 已被注释掉，
		// 而 mark() 压入的栈永远没有出栈 —— 每次符号查找都会泄漏一个 Long。计时需要成对使用。
		try {
			//even if a class/package cannot be found in the current module and among packages in modules
			//it depends on that are exported for any or this module, the class/package may exist internally
			//in some of these modules, or may exist in a module on which this module does not depend.
			//Provide better diagnostic in such cases by looking for the class in any module:
			Iterable<? extends S> candidates = get.apply(name);

			for (S sym : candidates) {
				if (validate.test(sym)) { return sym; }
			}

			Set<ModuleSymbol> recoverableModules = new HashSet<>(syms.getAllModules());

			recoverableModules.add(syms.unnamedModule);
			if (env != null) recoverableModules.remove(env.toplevel.modle);

			for (ModuleSymbol ms : recoverableModules) {
				//avoid overly eager completing classes from source-based modules, as those
				//may not be completable with the current compiler settings:
				if (ms.sourceLocation == null) {
					if (ms.classLocation == null) {
						ms = moduleFinder.findModule(ms);
					}

					if (ms.kind != ERR) {
						S sym = load.apply(ms, name);

						if (sym != null && validate.test(sym)) {
							return sym;
						}
					}
				}
			}

			return defaultResult;
		} finally {
			// Times.printElapsed("lookupInvisibleSymbol in @ms, @", name.charAt(0));
		}
	}

	private static long off_stateValue;
	public static void init() throws Throwable {
		off_stateValue = unsafe.objectFieldOffset(CompileState.class.getDeclaredField("value"));
		/* 使语法解析不会stop继续编译 */
		unsafe.putInt(CompileState.INIT, off_stateValue, 100);
		JavacTargeting.replaceSource();
	}
	static boolean first = true;
	/** 优先级很高 */
	public static void process(Set<? extends Element> rootElements) {
		// if (true) return;

		try {
			Times.mark();
			Map<JCTree, JCCompilationUnit> map = new HashMap<>();

			rootElements.forEach(element -> {
				TreePath path = trees.getPath(element);
				if (path == null) return;
				JCCompilationUnit unit = (JCCompilationUnit) path.getCompilationUnit();
				JCTree            cdef = trees.getTree(element);
				map.put(cdef, unit);
			});
			if (false) saveAllApi(map);

			// desugarStringTemplate.thenRuns.clear();
			map.forEach((cdef, unit) -> {
				// copyValueProc.translateTopLevelClass(unit);
				defaultToStatic.translateTopLevelClass(unit, cdef);
				// desugarStringTemplate.translateTopLevelClass(unit, cdef);
				desugarRecord.translateTopLevelClass(unit, cdef);
			});
			// desugarStringTemplate.thenRuns.forEach(Runnable::run);
		} catch (Throwable e) {
			err(e);
		} finally {
			Times.printElapsed("Process (init) in @ms");
		}
	}
	private static void saveAllApi(Map<JCTree, JCCompilationUnit> rootElements) {
		if (!first) return;
		first = false;

		StringBuilder     sb  = new StringBuilder();
		List<ClassSymbol> cpy = List.from(syms.getAllClasses());
		for (ClassSymbol c : cpy) {
			String string = c.toString();
			if (string.startsWith("arc") || string.startsWith("mindustry") || string.startsWith("rhino")) {
				if (c.members_field == null) continue;
				// 添加api到sb
				sb.append("class " + c.getQualifiedName() + " {\n");
				for (Symbol s : c.members_field.getSymbols()) {
					// 判断是否是合成的
					if ((s.flags() & Flags.SYNTHETIC) != 0) continue;
					sb.append(s.name).append(s.type).append(" ").append("\n");
				}
				sb.append("}\n");
			}
		}
		sb.append("-------------mod-tools(project)-----------------\n");
		for (Entry<JCTree, JCCompilationUnit> entry : rootElements.entrySet()) {
			JCTree            cdef = entry.getKey();
			JCCompilationUnit unit = entry.getValue();
			// sb.append(unit.sourcefile.getName()).append("\n");
			sb.append("`").append(unit).append("\n`");
		}

		String fileName = "F:/classes/api.txt";
		try (FileOutputStream fos = new FileOutputStream(fileName);
		     // OutputStreamWriter 将字符流转换为字节流，并指定编码
		     OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
			osw.write(convertUnicodeEscapes(sb.toString()));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	public static String convertUnicodeEscapes(String input) {
		// 匹配 \\u 后面跟着四个十六进制字符 (0-9, a-f, A-F)
		// \\u 匹配字面量 \\u
		// ([0-9a-fA-F]{4}) 匹配四个十六进制字符，并将其捕获到分组 1 中
		Pattern pattern = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
		Matcher matcher = pattern.matcher(input);
		// 使用 StringBuffer 来构建新的字符串
		StringBuffer sb = new StringBuffer();
		while (matcher.find()) {
			// 获取捕获到的十六进制字符串 (分组 1)
			String hex = matcher.group(1);
			try {
				// 将十六进制字符串解析为整数 (Unicode 码点)
				int codePoint = Integer.parseInt(hex, 16);
				// 将码点转换为字符 (可能是一个或两个 char，处理 BMP 和非 BMP)
				// Character.toChars 会返回一个 char[]
				char[] chars = Character.toChars(codePoint);
				// 将 char[] 转换为 String
				String replacement = new String(chars);
				// 将匹配到的 \\uXXXX 替换为转换后的字符
				// 使用 quoteReplacement 确保 replacement 中的 $ 和 \ 不被误解为 matcher 的特殊语法
				matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
			} catch (NumberFormatException e) {
				// 如果十六进制字符串无效，这里会捕获异常
				// 你可以选择跳过这个无效序列，或者保留它，或者记录错误
				System.err.println("警告: 发现无效的 Unicode 转义序列 \\u" + hex + "，无法转换。");
				// 保留原始的无效序列：
				matcher.appendReplacement(sb, "\\\\u" + hex); // 重新添加原始序列
				// 或者跳过（不 appendReplacement 即可） - 但 appendReplacement 确保其他部分被添加
				// 如果想完全跳过，需要更复杂的逻辑，或者直接将原始匹配到的文本加回去
				// 这里的 appendReplacement("\\\\u" + hex) 是保留原始文本的简单方法
			}
		}
		// 将字符串的剩余部分添加到 StringBuffer
		matcher.appendTail(sb);
		return sb.toString();
	}
	public static Context context() {
		return context;
	}
	private static class MyTaskListener implements TaskListener {
		private final TopTranslator topTranslator;
		public MyTaskListener(TopTranslator topTranslator) { this.topTranslator = topTranslator; }
		public void finished(TaskEvent e) {
			if (e.getKind() == TaskEvent.Kind.ANALYZE) {
				try {
					topTranslator.scanToplevel((JCCompilationUnit) e.getCompilationUnit());
				} catch (CheckException _) { } catch (Throwable ex) {
					SPrinter.err(ex);
				}
			}
		}
	}
}
