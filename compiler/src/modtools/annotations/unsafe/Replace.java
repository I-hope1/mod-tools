package modtools.annotations.unsafe;

import com.sun.source.util.*;
import com.sun.tools.javac.api.*;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Directive.ExportsDirective;
import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.Lint.LintCategory;
import com.sun.tools.javac.code.Source.Feature;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types.SimpleVisitor;
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
import modtools.annotations.unsafe.TopTranslator.CheckException;

import javax.annotation.processing.*;
import javax.lang.model.element.Element;
import javax.tools.JavaFileObject;
import java.io.*;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.Collectors;

import static com.sun.tools.javac.code.Kinds.Kind.ERR;
import static com.sun.tools.javac.util.Iterators.createCompoundIterator;
import static modtools.annotations.HopeReflect.*;
import static modtools.annotations.PrintHelper.SPrinter.*;
import static modtools.annotations.PrintHelper.errs;

public class Replace {
	public static Source targetVersion = Source.JDK8;

	static Context       context;
	static ClassFinder   classFinder;
	static Symtab        syms;
	static Enter         enter;
	static JavacTrees    trees;
	static Names         ns;
	static TreeMaker     maker;
	static JavacElements elements;
	static ModuleFinder  moduleFinder;
	static JavacMessages messages;
	static Analyzer      analyzer;
	static Filer         filer;

	public static JavaCompiler compiler;
	public static Log          log;

	static DefaultToStatic       defaultToStatic;
	static DesugarStringTemplate desugarStringTemplate;
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
		desugarStringTemplate = new DesugarStringTemplate(context);
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
		extendingFunc(getContextFor(processingEnv));
	}

	public static final HashMap<ModuleSymbol, ClassSymbol> moduleRepresentClass = new HashMap<>();
	public static void searchModuleExport(ModuleSymbol module) {
		if (moduleRepresentClass.containsKey(module)) return;
		module.exports.stream().filter(export -> export.modules == null).findFirst().ifPresent(d -> {
			if (d.packge.members_field == null) return;
			moduleRepresentClass.put(module, (ClassSymbol) d.packge.members_field.getSymbols(s -> s instanceof ClassSymbol).iterator().next());
		});
	}

	private static void extendingFunc0() throws Exception {
		accessOverride();

		forceJavaVersion();

		forcePreview();

		moduleExports();

		other();
	}
	private static void moduleExports() throws Exception {
		DeferredDiagnosticHandler handler = getAccess(Log.class, Log.instance(context), "diagnosticHandler");

		handler.getDiagnostics().stream().filter(d -> d.isFlagSet(DiagnosticFlag.RESOLVE_ERROR))
		 .filter(d -> d.getArgs()[0] instanceof PackageSymbol)
		 .forEach(d -> {
			 PackageSymbol pkg = (PackageSymbol) d.getArgs()[0];
			 needExportedApi.add(pkg);
			 searchModuleExport(pkg.modle);
		 });

		Method initModule = Modules.class.getDeclaredMethod("setupAutomaticModule", ModuleSymbol.class);
		initModule.setAccessible(true);
		Modules modules = Modules.instance(context);

		Map<ModuleSymbol, Set<ExportsDirective>> addExports = getAccess(Modules.class, modules, "addExports");
		Consumer<ModuleSymbol> exportAll = m -> {
			var prev = m.exports;
			try {
				initModule.invoke(modules, m);
			} catch (IllegalAccessException | InvocationTargetException e) {
				throw new RuntimeException(e);
			}

			Set<ExportsDirective> set = m.exports.stream().collect(Collectors.toSet());
			set.removeIf(d -> d.packge.fullname.isEmpty());
			m.exports = List.from(set);

			for (ExportsDirective export : m.exports) {
				export.packge.modle = m;
				addExports.computeIfAbsent(m, k -> new HashSet<>()).add(new ExportsDirective(export.packge, List.of(syms.unnamedModule)));
			}
			m.exports = prev;
		};
		Field field = Symtab.class.getDeclaredField("modules");
		field.setAccessible(true);
		var moduleMap = (Map<Name, ModuleSymbol>) field.get(syms);
		var module    = moduleMap.get(ns.fromString("jdk.hotspot.agent"));
		modules.allModules().add(module);
		for (ModuleSymbol m : modules.allModules()) {
			exportAll.accept(m);
		}
		// exportAll.accept(moduleFinder.findModule(ns.fromString("jdk.unsupported")));
	}

	static Symbol NOT_FOUND;
	/**
	 * 包括{@code Module}访问
	 * @see Resolve#doRecoveryLoadClass
	 */
	private static void accessOverride() {
		Resolve prev = Resolve.instance(context);
		removeKey(Resolve.class, () -> tryDefineOne(prev));
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
		setAccess(Resolve.class, resolve, "accessibilityChecker", new SimpleVisitor<>() {
			public Object visitType(Type t, Object o) { return t; }
		});
	}
	private static Resolve tryDefineOne(Resolve resolve) {
		boolean hasAnnotation = isHasAnnotation();
		var     predicate     = (BiPredicate<Env<AttrContext>, Symbol>) (env, __) -> hasAnnotation && env.enclClass.sym.getAnnotation(NoAccessCheck.class) != null;

		try {
			return new MyResolve(context, predicate);
		} catch (Exception ignored) { }

		return resolve;
	}
	private static boolean isHasAnnotation() {
		try {
			NoAccessCheck.class.getClass();
		} catch (NoClassDefFoundError e) { return false; }
		return true;
	}
	private static void other() throws ClassNotFoundException, IOException {
		// removeKey(MemberEnter.class, () -> new MyMemberEnter(context));

		fixSyntaxErrorAndSkipInvisibleError();

		// 适配d8无法编译jdk21的枚举(enum)的字节码
		Options.instance(context).put(Option.PARAMETERS, "");

		removeKey(TransPatterns.class, () -> new MyTransPatterns(context));

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
	public static void fixSyntaxErrorAndSkipInvisibleError() {
		DeferredDiagnosticHandler handler = getAccess(Log.class, Log.instance(context), "diagnosticHandler");
		ListBuffer<JCDiagnostic>  buffer  = new ListBuffer<>();

		int[] positionOffset = {0};

		boolean illegalStartOf = true;
		// handler.getDiagnostics()
		//  .stream().forEach(diag -> println(diag.getMessage(Locale.getDefault()) + ":" + getAccess(JCDiagnostic.class, diag, "flags")));
		buffer.addAll(handler.getDiagnostics()
		 .stream()
		 .filter(diag -> {
			 // skip包不可见的错误
			 if (diag.isFlagSet(DiagnosticFlag.RESOLVE_ERROR) && diag.getCode().contains("package.not.visible")) return false;
			 // 语法错误
			 if (!diag.isFlagSet(DiagnosticFlag.SYNTAX)) return true;

			 try {
				 JavaFileObject filer = diag.getSource();
				 String[]       args  = Arrays.stream(diag.getArgs()).map(String::valueOf).toArray(String[]::new);
				 if (args.length == 0) {
					 if (illegalStartOf && diag.getCode().contains("illegal.start.of")) {
						 StringBuilder target = new StringBuilder(filer.getCharContent(true));
						 try (var input = new FileOutputStream(new File(filer.toUri()))) {
							 input.write(target.deleteCharAt((int) diag.getPosition() + positionOffset[0]).toString().getBytes());
							 positionOffset[0]--;
						 }
						 return false;
					 }
					 // println(diag);
					 return true;
				 }
				 if (!(args[0].length() == 3 && args[0].charAt(0) == '\'' && args[0].charAt(2) == '\'')) return true;

				 errs(
					"Added " + args[0] + " at " + filer.getName() + "(" + diag.getLineNumber() + ":" + diag.getColumnNumber() + ")"
				 );
				 StringBuilder target = new StringBuilder(filer.getCharContent(true));
				 int           index  = (int) diag.getPosition() + positionOffset[0];
				 if (target.charAt(index) == ')') {
					 target.deleteCharAt(index);
				 } else {
					 target.insert(index, args[0].charAt(1));
				 }
				 positionOffset[0]++;

				 try (var input = new FileOutputStream(new File(filer.toUri()))) {
					 input.write(target.toString().getBytes());
				 }
				 return false;
			 } catch (Throwable e) {
				 err(e);
			 }
			 return true;
		 }).toList());
		setAccess(DeferredDiagnosticHandler.class, handler, "deferred", buffer);
	}

	public static void forceJavaVersion() {
		Options.instance(context).keySet().stream()
		 .filter(f -> f.startsWith("-AtargetVersion=")).findFirst()
		 .map(v -> v.substring("-AtargetVersion=".length()))
		 .map(Target::lookup).ifPresent(Replace::setTarget);
		// Options.instance(context).keySet().stream()
		//  .filter(f -> f.startsWith("-AtargetVersion=")).findFirst().ifPresent(f -> Options.instance(context).remove(f));
	}
	/** 使source8就可以支持所有特性 */
	public static void replaceSource() {
		long off = fieldOffset(Feature.class, "minLevel");
		for (Feature feature : Feature.values()) {
			if (!feature.allowedInSource(targetVersion)) {
				unsafe.putObject(feature, off, targetVersion);
			}
		}
	}
	private static void forcePreview() {
		try {
			forceEnablePreview0();
		} catch (NoClassDefFoundError | NoSuchMethodError _) { }
	}

	private static void forceEnablePreview0() {
		Preview preview = Preview.instance(context);
		if (!preview.isEnabled()) {
			setAccess(Preview.class, preview, "enabled", true);
		}
		Lint.instance(context).suppress(LintCategory.PREVIEW);
		runIgnoredException(() -> Check.instance(context).disablePreviewCheck = true);
		setAccess(Preview.class, preview, "sourcesWithPreviewFeatures", new HashSet<>() {
			public boolean contains(Object o) {
				return false;
			}
		});
	}
	private static void setTarget(Target target) {
		if (target == null) return;
		removeKey(Target.class, () -> target);
		println("targetVersion: @ (@)", target.ordinal() + 1, target);

		// jdk9才有
		removeKey("concatKey", StringConcat.class, null);

		// removeKey(ClassWriter.class, () -> new MyClassWriter(context));
		// setAccess(JavaCompiler.class, JavaCompiler.instance(context), "writer", ClassWriter.instance(context));

		// 用于适配低版本
		runIgnoredException(() -> setAccess(Lower.class, Lower.instance(context), "useMatchException", false));
		setAccess(Lower.class, Lower.instance(context), "target", target);
		setAccess(LambdaToMethod.class, LambdaToMethod.instance(context), "nestmateLambdas", false);
		setAccess(Gen.class, Gen.instance(context), "concat", StringConcat.instance(context));
		setAccess(ClassWriter.class, ClassWriter.instance(context), "target", target);
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
		Times.mark();
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

	private static void runIgnoredException(Runnable r) { try { r.run(); } catch (Throwable ignored) { } }
	static void setValue(Class<?> cl, String key, Object val) {
		Object instance = invoke(cl, null, "instance", new Object[]{context}, Context.class);
		setAccess(cl, instance, key, val);
	}
	private static <T> void re_init(Class<T> clazz, T instance) throws Throwable {
		MethodHandle init = InitHandleC.findInitDesktop(clazz, clazz.getDeclaredConstructor(Context.class), clazz);
		init.invoke(instance, context);
	}

	/// ------------------------------

	private static void removeKey(Class<?> cls) {
		removeKey(cls, null);
	}
	public static <T> void removeKey(Class<T> cls, Supplier<T> newVal) {
		String s = (cls.isMemberClass() ? cls.getNestHost().getSimpleName() : "") + cls.getSimpleName();
		if (s.startsWith("JC")) s = s.substring(2);
		removeKey(s.substring(0, 1).toLowerCase() + s.substring(1) + "Key", cls, newVal);
	}
	private static <T> void removeKey(String name, Class<T> cls, Supplier<T> newVal) {
		Key<Resolve>            key  = getAccess(cls, null, name);
		HashMap<Key<?>, Object> ht   = getAccess(Context.class, context, "ht");
		Object                  prev = ht.remove(key);
		if (newVal != null) {
			Object value = newVal.get();
			// if (prev != null) copyTo(prev, value);
			if (!ht.containsKey(key)) ht.put(key, value);
		}
	}

	private static <T> void copyTo(T src, T dest) {
		Class<?> clazz = src.getClass();
		while (clazz != Object.class) {
			for (Field field : clazz.getDeclaredFields()) {
				if (field.getType().isPrimitive()) continue;
				int mod = field.getModifiers();
				if (Modifier.isStatic(mod)) continue;
				long off = unsafe.objectFieldOffset(field);
				unsafe.putObject(dest, off, unsafe.getObject(src, off));
				// println(unsafe.getObject(dest, off));
			}
			clazz = clazz.getSuperclass();
		}
	}

	private static long off_stateValue;
	public static void init() throws Throwable {
		off_stateValue = unsafe.objectFieldOffset(CompileState.class.getDeclaredField("value"));
		/* 使语法解析不会stop继续编译 */
		unsafe.putInt(CompileState.INIT, off_stateValue, 100);
		replaceSource();
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

			desugarStringTemplate.thenRuns.clear();
			map.forEach((cdef, unit) -> {
				// copyValueProc.translateTopLevelClass(unit);
				defaultToStatic.translateTopLevelClass(unit, cdef);
				desugarStringTemplate.translateTopLevelClass(unit, cdef);
				desugarRecord.translateTopLevelClass(unit, cdef);
			});
			desugarStringTemplate.thenRuns.forEach(Runnable::run);
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
	public static Context getContextFor(ProcessingEnvironment env) {
		if (env instanceof JavacProcessingEnvironment proc) {
			return proc.getContext();
		} else if (Proxy.isProxyClass(env.getClass())) {
			var handler = Proxy.getInvocationHandler(env);
			// 尝试从 Handler 中找到原始的 ProcessingEnvironment
			// 因为这是 IDEA 的匿名内部类，字段名不确定，所以遍历所有字段查找
			for (Field field : handler.getClass().getDeclaredFields()) {
				try {
					field.setAccessible(true);
					Object obj = field.get(handler);
					// 递归调用自己，尝试解包找到的对象
					if (obj instanceof ProcessingEnvironment) {
						try {
							return getContextFor((ProcessingEnvironment) obj);
						} catch (Exception e) {
							// 如果这个字段不是我们要找的，继续找下一个
						}
					}
				} catch (IllegalAccessException e) {
					// 忽略
				}
			}

			// 如果遍历完还没找到，再抛出原来的异常作为兜底
			StringBuilder sb = new StringBuilder();
			sb.append("Failed to unwrap proxy: ").append(handler).append("\n");
			throw new RuntimeException(sb.toString());
		} else {
			try {
				Field f = env.getClass().getDeclaredField("delegate");
				f.setAccessible(true);
				return ((JavacProcessingEnvironment) f.get(env)).getContext();
				// CMN.Log(jcEnv);
			} catch (Throwable e) {
				StringBuilder sb = new StringBuilder();
				for (Field field : env.getClass().getDeclaredFields()) {
					sb.append(field).append("\n");
				}
				throw new RuntimeException("" + sb);
			}
		}
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
