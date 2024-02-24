package modtools.annotations.unsafe;

import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Source.Feature;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Types.SimpleVisitor;
import com.sun.tools.javac.comp.*;
import com.sun.tools.javac.comp.Resolve.RecoveryLoadClass;
import com.sun.tools.javac.jvm.*;
import com.sun.tools.javac.main.*;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Context.Key;
import modtools.annotations.*;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Supplier;

import static modtools.annotations.HopeReflect.*;
import static modtools.annotations.PrintHelper.SPrinter.*;

public class Replace {
	static Context context;
	public static void extendingFunc(Context context) {
		Replace.context = context;
		try {
			extendingFunc0();
		} catch (Throwable e) {err(e);}
	}

	private static void extendingFunc0() {
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
		Resolve resolve = Resolve.instance(context);
		try {
			NoAccessCheck.class.getClass();
			removeKey(Resolve.class);
			resolve = new MyResolve(context);
		} catch (NoClassDefFoundError ignored) {}

		ModuleFinder moduleFinder = ModuleFinder.instance(context);
		NOT_FOUND = getAccess(Resolve.class, resolve, "typeNotFound");

		final Symtab symtab = Symtab.instance(context);
		setAccess(Resolve.class, resolve, "doRecoveryLoadClass", (RecoveryLoadClass) (env, name) -> {
			var candidates = Convert.classCandidates(name);
			// println("candidates: @", candidates);
			Iterator<ClassSymbol> iterator = Iterators.createCompoundIterator(candidates,
			 c -> symtab.getClassesForName(c).iterator());
			// find def
			while (iterator.hasNext()) {
				ClassSymbol next = iterator.next();
				if (next != null) return next;
			}
			// find in other module
			final Set<ModuleSymbol> recoverableModules = new HashSet<>(symtab.getAllModules());

			recoverableModules.add(symtab.unnamedModule);
			recoverableModules.remove(env.toplevel.modle);

			for (ModuleSymbol ms : recoverableModules) {
				//avoid overly eager completing classes from source-based modules, as those
				//may not be completable with the current compiler settings:
				if (ms.sourceLocation == null) {
					if (ms.classLocation == null) {
						ms = moduleFinder.findModule(ms);
					}

					Symbol sym = loadClass(ms, candidates);
					if (sym != null) {
						println("Found invisible symbol: @", sym);
						// invisibleSymbols.add((ClassSymbol) sym);
						return sym;
					}
				}
			}
			return NOT_FOUND;
		});
		setAccess(Resolve.class, resolve, "accessibilityChecker", new SimpleVisitor<>() {
			public Object visitType(Type t, Object o) {return t;}
		});

		if (resolve.getClass() == Resolve.class) return;
		setAccess(Check.class, Check.instance(context), "rs", resolve);
		setAccess(Attr.class, Attr.instance(context), "rs", resolve);
	}
	private static void other() {
		removeKey(MemberEnter.class, () -> new MyMemberEnter(context));

		Options.instance(context).put(Option.PARAMETERS, "");
		// removeKey(ClassWriter.class, () -> new MyClassWriter(context));
		// setAccess(JavaCompiler.class, JavaCompiler.instance(context), "writer", ClassWriter.instance(context));

		/* removeKey(Check.class);

		try (InputStream in = Replace.class.getClassLoader().getResourceAsStream("modtools/annotations/unsafe/MyCheck$1.class")) {
			byte[] bytes = in.readAllBytes();
			Unsafe.getUnsafe().defineClass0(null, bytes, 0, bytes.length, Check.class.getClassLoader(), null);
		} catch (IOException e) {
			err(e);
		}
		try (InputStream in = Replace.class.getClassLoader().getResourceAsStream("modtools/annotations/unsafe/SpecialTreeVisitor.class")) {
			byte[] bytes = in.readAllBytes();
			Unsafe.getUnsafe().defineClass0(null, bytes, 0, bytes.length, Check.class.getClassLoader(), null);
		} catch (IOException e) {
			err(e);
		}
		try (InputStream in = Replace.class.getClassLoader().getResourceAsStream("com/sun/tools/javac/comp/MyCheck.class")) {
			byte[]         bytes       = in.readAllBytes();
			Class<?>       newCheckClazz    = Unsafe.getUnsafe().defineClass0(null, bytes, 0, bytes.length, Check.class.getClassLoader(), null);
			Constructor<?> constructor = newCheckClazz.getDeclaredConstructor(Context.class);
			setAccessible(constructor);
			Check check = (Check) constructor.newInstance(context);
			Method method = Check.class.getDeclaredMethod("checkFlags", DiagnosticPosition.class, long.class, Symbol.class, JCTree.class);
			setAccessible(method);
			println(method.invoke(check, null, 0, null, null));
			setAccess(MemberEnter.class, MemberEnter.instance(context), "chk", check);
		} catch (Throwable th) {
			err(th);
		} */
		// Check check = new MyCheck(context);
	}
	static Symbol loadClass(ModuleSymbol ms, List<Name> candidates) {
		for (Name candidate : candidates) {
			if (ms.kind != Kind.ERR) {
				try {
					ClassSymbol symbol = BaseProcessor.classFinder.loadClass(ms, candidate);
					if (symbol.exists()) return symbol;
					// println("source: @", symbol.sourcefile);
				} catch (CompletionFailure ignored) {}
			}
		}
		return null;
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
		Preview preview = Preview.instance(context);
		if (!preview.isEnabled()) {
			setAccess(Preview.class, preview, "enabled", true);
			// setAccess(Preview.class, preview, "forcePreview", true);
		}
		setAccess(Preview.class, preview, "sourcesWithPreviewFeatures", new HashSet<>() {
			public boolean contains(Object o) {
				return false;
			}
		});
		// setAccess(ClassWriter.class, ClassWriter.instance(context), "target", target);
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
		setAccess(Gen.class, Gen.instance(context), "concat", StringConcat.instance(context));
		setAccess(ClassWriter.class, ClassWriter.instance(context), "target", target);
	}

	private static void runIgnoredException(Runnable r) {try {r.run();} catch (Throwable ignored) {}}
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
}


/*
class Unset {
	static void a() {
		for (ModuleSymbol module : mSymtab.getAllModules()) {
			module.complete();
			module.readModules = new HashSet<>() {
				public boolean contains(Object o) {
					return true;
				}
			};
			Map<Name, PackageSymbol> map = new HashMap<>(module.visiblePackages);
			module.visiblePackages = new LinkedHashMap<>(map) {
				public boolean containsKey(Object key) {
					return true;
				}
				public PackageSymbol get(Object key) {
					return map.computeIfAbsent((Name) key, __ -> {
						PackageSymbol symbol = mSymtab.enterPackage(module, (Name) key);
						if (symbol.name != key) put(symbol.name, symbol);
						symbol.modle = module;
						return symbol;
					});
				}
			};
		}
	}
}*/
