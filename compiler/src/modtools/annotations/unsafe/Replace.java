package modtools.annotations.unsafe;

import com.sun.source.util.*;
import com.sun.tools.javac.api.*;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Directive.ExportsDirective;
import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.Lint.LintCategory;
import com.sun.tools.javac.code.Source.Feature;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Types.SimpleVisitor;
import com.sun.tools.javac.comp.*;
import com.sun.tools.javac.comp.CompileStates.CompileState;
import com.sun.tools.javac.comp.Resolve.RecoveryLoadClass;
import com.sun.tools.javac.jvm.*;
import com.sun.tools.javac.main.Option;
import com.sun.tools.javac.model.JavacElements;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.Context.Key;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticFlag;
import com.sun.tools.javac.util.Log.DeferredDiagnosticHandler;
import modtools.annotations.*;
import modtools.annotations.unsafe.CheckDefaultCall.CheckException;

import javax.lang.model.element.Element;
import javax.tools.JavaFileObject;
import java.io.*;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;

import static com.sun.tools.javac.code.Kinds.Kind.ERR;
import static com.sun.tools.javac.util.Iterators.createCompoundIterator;
import static modtools.annotations.HopeReflect.*;
import static modtools.annotations.PrintHelper.SPrinter.*;
import static modtools.annotations.PrintHelper.errs;

public class Replace {
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

	static CopyValueProc         copyValueProc;
	static DefaultToStatic       defaultToStatic;
	static DesugarStringTemplate desugarStringTemplate;
	static DesugarRecord         desugarRecord;
	static Properties            bundles = new Properties();
	public static void extendingFunc(Context context) {
		/* 恢复初始状态 */
		unsafe.putInt(CompileState.INIT, off_stateValue, 0);
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

		copyValueProc = new CopyValueProc();
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
		try {
			extendingFunc0();
		} catch (Throwable e) { err(e); }
	}

	public static final HashMap<ModuleSymbol, ClassSymbol> moduleRepresentClass = new HashMap<>();
	public static void searchModuleExport(ModuleSymbol module) {
		if (moduleRepresentClass.containsKey(module)) return;
		module.exports.stream().filter(export -> export.modules == null).findFirst().ifPresent(d -> {
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
	private static void setRecovery(Resolve resolve) {
		setAccess(Resolve.class, resolve, "doRecoveryLoadClass", (RecoveryLoadClass) (env, name) -> {
			List<Name> candidates = Convert.classCandidates(name);
			return lookupInvisibleSymbol(env, name,
			 n -> () -> createCompoundIterator(candidates,
				c -> syms.getClassesForName(c).iterator()),
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

		fixSyntaxError();

		// 适配d8无法编译jdk21的枚举(enum)的字节码
		Options.instance(context).put(Option.PARAMETERS, "");

		removeKey(TransPatterns.class, () -> new MyTransPatterns(context));

		MultiTaskListener.instance(context).add(new TaskListener() {
			final CheckDefaultCall chk = new CheckDefaultCall(context);
			public void finished(TaskEvent e) {
				if (e.getKind() == TaskEvent.Kind.ANALYZE) {
					try {
						chk.scanToplevel((JCCompilationUnit) e.getCompilationUnit());
					} catch (CheckException _) { }
				}
			}
		});
		// removeKey(TransTypes.class, () -> new MyTransTypes(context));
		// setAccess(JavaCompiler.class, JavaCompiler.instance(context), "transTypes", TransTypes.instance(context));
		// removeKey(Lower.class, () -> new Desugar(context));
		// Lower lower = Lower.instance(context);
		// setAccess(JavaCompiler.class, JavaCompiler.instance(context), "lower", lower);
		// setAccess(Gen.class, Gen.instance(context), "lower", lower);
	}
	public static final HashSet<PackageSymbol> needExportedApi = new HashSet<>();
	public static void fixSyntaxError() {
		DeferredDiagnosticHandler handler = getAccess(Log.class, Log.instance(context), "diagnosticHandler");
		ListBuffer<JCDiagnostic>  buffer  = new ListBuffer<>();

		int[] positionOffset = {0};

		buffer.addAll(handler.getDiagnostics()
		 .stream()
		 .filter(diag -> diag.isFlagSet(DiagnosticFlag.SYNTAX))
		 .filter(t -> {
			 try {
				 JavaFileObject filer = t.getSource();
				 String[]       args  = Arrays.stream(t.getArgs()).map(String::valueOf).toArray(String[]::new);
				 if (args.length == 0) return true;
				 if (!(args[0].length() == 3 && args[0].charAt(0) == '\'' && args[0].charAt(2) == '\'')) return true;

				 errs(
					"Added " + args[0] + " at " + filer.getName() + "(" + t.getLineNumber() + ":" + t.getColumnNumber() + ")"
				 );
				 StringBuilder target = new StringBuilder(filer.getCharContent(true));
				 int           index  = (int) t.getPosition() + positionOffset[0];
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
		long   off    = fieldOffset(Feature.class, "minLevel");
		Source source = Source.JDK8;
		for (Feature feature : Feature.values()) {
			if (!feature.allowedInSource(source)) {
				unsafe.putObject(feature, off, source);
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


	public static <S extends Symbol> Symbol lookupInvisibleSymbol(
	 Env<AttrContext> env,
	 Name name,
	 Function<Name, Iterable<S>> get,
	 BiFunction<ModuleSymbol, Name, S> load,
	 Predicate<S> validate,
	 Symbol defaultResult) {
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
	}

	private static void runIgnoredException(Runnable r) { try { r.run(); } catch (Throwable ignored) { } }
	static void setValue(Class<?> cl, String key, Object val) {
		Object instance = invoke(cl, null, "instance", new Object[]{context}, Context.class);
		setAccess(cl, instance, key, val);
	}
	private static <T> void re_init(Class<T> clazz, T instance) throws Throwable {
		MethodHandle init = InitHandle.findInitDesktop(clazz, clazz.getDeclaredConstructor(Context.class), clazz);
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
	/** 优先级很高 */
	public static void process(Set<? extends Element> rootElements) {
		try {
			Times.mark();
			rootElements.forEach(element -> {
				TreePath path = trees.getPath(element);
				if (path == null) return;
				JCCompilationUnit unit = (JCCompilationUnit) path.getCompilationUnit();
				JCTree            cdef = trees.getTree(element);

				// copyValueProc.translateTopLevelClass(unit);
				defaultToStatic.translateTopLevelClass(unit, cdef);
				desugarStringTemplate.translateTopLevelClass(unit, cdef);
				desugarRecord.translateTopLevelClass(unit, cdef);
			});
		} catch (Throwable e) {
			err(e);
		} finally {
			Times.printElapsed("Process (init) in @ms");
		}
	}
	public static Context context() {
		return context;
	}
}
