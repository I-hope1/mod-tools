package modtools.annotations.unsafe;

import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types.SimpleVisitor;
import com.sun.tools.javac.comp.*;
import com.sun.tools.javac.jvm.*;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Context.Key;
import modtools.annotations.HopeReflect;

import java.io.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;

import static modtools.annotations.BaseProcessor.*;
import static modtools.annotations.HopeReflect.*;
import static modtools.annotations.PrintHelper.SPrinter.println;

public class Replace {
	public static void extendingFunc() throws IllegalAccessException, IOException {
		accessOverride();

		forceJavaVersion();
	}
	private static void accessOverride() throws IllegalAccessException {
		// Resolve prev = Resolve.instance(__context);
		removeKey(Resolve.class);
		Resolve resolve = new MyResolve(__context);
		// copyTo(prev, resolve);

		setAccess(Resolve.class, resolve, "accessibilityChecker", new SimpleVisitor<>() {
			public Object visitType(Type t, Object o) {return t;}
		});
		setAccess(Check.class, Check.instance(__context), "rs", resolve);
		setAccess(Attr.class, __attr__, "rs", resolve);
	}
	static void forceJavaVersion() throws IOException {
		Properties properties = new Properties();
		properties.load(new FileInputStream("gradle.properties"));
		// Target prev = Target.instance(__context);
		setTarget(properties);
	}
	private static void setTarget(Properties properties) {
		Target target = Target.lookup(properties.getProperty("targetVersion"));
		println("targetVersion: @", target);
		try {
			MethodHandle handle = lookup.findConstructor(Target.class, MethodType.methodType(void.class, String.class, int.class, String.class, int.class, int.class));
			target = (Target) handle.invoke("JDK_17_" + Math.random(), 17, "17", target.majorVersion, target.minorVersion);
		} catch (Throwable e) {
			return;
		}
		// println((Object) getAccess(ClassWriter.class, ClassWriter.instance(__context), "target"));
		// removeKey(Target.class, target);

		setAccess(ClassWriter.class, ClassWriter.instance(__context), "target", target);
	}
	/* private static void setSource(Properties properties) {
		Source source = Source.lookup(properties.getProperty("targetVersion"));
		println("targetVersion: @", source);
		try {
			MethodHandle handle = lookup.findConstructor(Source.class, MethodType.methodType(void.class, String.class, int.class, String.class));
			source = (Source) handle.invoke("JDK_17_" + Math.random(), 17, "17");
		} catch (Throwable e) {
			return;
		}
	} */

	/// ------------------------------

	private static void removeKey(Class<?> cls) {
		removeKey(cls, null);
	}
	private static void removeKey(Class<?> cls, Object newVal) {
		Key<Resolve>            key = HopeReflect.getAccess(cls, null, cls.getSimpleName().toLowerCase() + "Key");
		HashMap<Key<?>, Object> ht  = HopeReflect.getAccess(Context.class, __context, "ht");
		ht.remove(key);
		if (newVal != null) ht.put(key, newVal);
	}

	private static <T> void copyTo(T src, T dest) throws IllegalAccessException {
		for (Field field : dest.getClass().getDeclaredFields()) {
			int mod = field.getModifiers();
			if (Modifier.isStatic(mod) || Modifier.isFinal(mod)) continue;
			println(field);
			field.setAccessible(true);
			field.set(dest, field.get(src));
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
