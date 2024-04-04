package modtools.annotations.unsafe;

import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.*;
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
import com.sun.tools.javac.main.*;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.Context.Key;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticFlag;
import com.sun.tools.javac.util.Log.DeferredDiagnosticHandler;
import modtools.annotations.NoAccessCheck;

import javax.tools.JavaFileObject;
import java.io.*;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

import static com.sun.tools.javac.code.Kinds.Kind.ERR;
import static com.sun.tools.javac.util.Iterators.createCompoundIterator;
import static modtools.annotations.HopeReflect.*;
import static modtools.annotations.PrintHelper.SPrinter.*;
import static modtools.annotations.PrintHelper.errs;

public class Replace {
	static Context      context;
	static ClassFinder  classFinder;
	static Symtab       syms;
	static Enter        enter;
	static JavacTrees   trees;
	static ModuleFinder moduleFinder;
	public static void extendingFunc(Context context) {
		/* 恢复初始状态 */
		unsafe.putInt(CompileState.INIT, off_stateValue, 0);
		Replace.context = context;
		classFinder = ClassFinder.instance(context);
		syms = Symtab.instance(context);
		enter = Enter.instance(context);
		trees = JavacTrees.instance(context);
		moduleFinder = ModuleFinder.instance(context);
		try {
			extendingFunc0();
		} catch (Throwable e) { err(e); }
	}

	private static void extendingFunc0() throws ClassNotFoundException {
		accessOverride();

		other();

		forcePreview();
		forceJavaVersion();
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
			Symbol existing = importScope.findFirst(Convert.shortName(name),
			 sym -> sym.kind == Kind.TYP && sym.flatName() == name);
			return existing;
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
	private static void other() throws ClassNotFoundException {
		// removeKey(MemberEnter.class, () -> new MyMemberEnter(context));

		// 适配d8无法编译jdk21的枚举(enum)的字节码
		Options.instance(context).put(Option.PARAMETERS, "");

		fixSyntaxError();

		// replaceAccess(Resolve.class,Resolve.instance(context), "resolveMethodCheck", "nilMethodCheck");

		if (true) return;
		// 忽略模块访问检查
		Object prev = getAccess(Resolve.class, Resolve.instance(context), "basicLogResolveHelper");
		setAccess(Resolve.class, Resolve.instance(context), "basicLogResolveHelper",
		 Proxy.newProxyInstance(Resolve.class.getClassLoader(), new Class[]{Class.forName("com.sun.tools.javac.comp.Resolve$LogResolveHelper")},
			(proxy, method, args) -> {
				if (method.getName().equals("resolveDiagnosticNeeded")) {
					return false;
				}
				setAccessible(method);
				return method.invoke(prev, args);
			}
		 ));
	}
	private static void fixSyntaxError() {
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

				 errs("Added " + args[0] + " at " + filer.getName() + "(" + t.getLineNumber() + ":" + t.getColumnNumber() + ")");
				 StringBuilder target = new StringBuilder(filer.getCharContent(true));
				 target.insert((int) t.getPosition() + positionOffset[0], args[0].charAt(1));
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

	static void forceJavaVersion() {
		Options.instance(context).keySet().stream()
		 .filter(f -> f.startsWith("-AtargetVersion=")).findFirst()
		 .map(v -> v.substring("-AtargetVersion=".length()))
		 .map(Target::lookup).ifPresent(Replace::setTarget);
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
			forceEnabledPreview0();
		} catch (NoClassDefFoundError ignored) { }
	}

	private static void forceEnabledPreview0() {
		Preview preview = Preview.instance(context);
		if (!preview.isEnabled()) {
			setAccess(Preview.class, preview, "enabled", true);
		}
		Lint.instance(context).suppress(LintCategory.PREVIEW);
		Check.instance(context).disablePreviewCheck = true;
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

		// removeKey(Target.class, target);
		// jdk9才有
		removeKey("concatKey", StringConcat.class, null);

		// removeKey(ClassWriter.class, () -> new MyClassWriter(context));
		// setAccess(JavaCompiler.class, JavaCompiler.instance(context), "writer", ClassWriter.instance(context));

		// 用于适配低版本
		// Symtab syms = Symtab.instance(context);
		// setAccess(Symtab.class, syms, "matchExceptionType", syms.incompatibleClassChangeErrorType);
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
			if (validate.test(sym))
				return sym;
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
	public static void removeKey(Class<?> cls, Supplier<Object> newVal) {
		String s = cls.getSimpleName();
		removeKey(s.substring(0, 1).toLowerCase() + s.substring(1) + "Key", cls, newVal);
	}
	private static void removeKey(String name, Class<?> cls, Supplier<Object> newVal) {
		Key<Resolve>            key = getAccess(cls, null, name);
		HashMap<Key<?>, Object> ht  = getAccess(Context.class, context, "ht");
		ht.remove(key);
		if (newVal != null) {
			Object value = newVal.get();
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

	static long off_stateValue;
	public static void init() throws Throwable {
		off_stateValue = unsafe.objectFieldOffset(CompileState.class.getDeclaredField("value"));
		/* 使语法解析不会stop继续编译 */
		unsafe.putInt(CompileState.INIT, off_stateValue, 100);
		replaceSource();
	}
}