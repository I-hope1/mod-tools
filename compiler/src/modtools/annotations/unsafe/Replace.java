package modtools.annotations.unsafe;

import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Types.SimpleVisitor;
import com.sun.tools.javac.comp.*;
import com.sun.tools.javac.jvm.*;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Context.Key;
import modtools.annotations.*;

import java.io.*;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.*;
import java.util.*;

import static modtools.annotations.BaseProcessor.*;
import static modtools.annotations.HopeReflect.setAccess;
import static modtools.annotations.PrintHelper.SPrinter.println;

public class Replace {
	public static void extendingFunc()
	 throws Throwable {
		accessOverride();

		forceJavaVersion();
	}
	private static void accessOverride() {
		try {
			NoAccessCheck.class.getClass();
		} catch (NoClassDefFoundError error) {return;}
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
	static void forceJavaVersion() throws Throwable {
		File file = new File("gradle.properties");
		if (!file.exists()) {
			if (file.createNewFile()) println("Created New File: @", file.getAbsoluteFile());
			else {
				println("Could not create file: @", file.getAbsoluteFile());
				return;
			}
		}
		Properties properties = new Properties();
		properties.load(new FileInputStream(file));

		// Target prev = Target.instance(__context);
		setTarget(properties);
		forcePreview();

		hasMindustry = !properties.containsKey("hasMindustry") || properties.getProperty("hasMindustry").equals("true");
	}
	private static void forcePreview() {
		Preview preview = Preview.instance(__context);
		if (!preview.isEnabled()) {
			setAccess(Preview.class, preview, "enabled", true);
			// setAccess(Preview.class, preview, "forcePreview", true);
		}
	}
	private static void setTarget(Properties properties) throws Throwable {
		String version = properties.getProperty("targetVersion");
		Target target  = Target.lookup(version);
		if (target == null) return;
		println("targetVersion: [@](@)", version, target);

		removeKey(Target.class, target);
		// jdk9才有
		removeKey("concatKey", StringConcat.class, null);

		// re_init(Arguments.class, Arguments.instance(__context));
		// re_init(LambdaToMethod.class, LambdaToMethod.instance(__context));
		// setAccess(RootPackageSymbol.class, mSymtab.rootPackage, "allowPrivateInvokeVirtual", target.runtimeUseNestAccess());

		// 用于适配低版本
		setAccess(Lower.class, Lower.instance(__context), "target", target);
		setAccess(ClassWriter.class, ClassWriter.instance(__context), "target", target);
	}
	private static <T> void re_init(Class<T> clazz, T instance) throws Throwable {
		MethodHandle init = InitHandle.findInitDesktop(clazz, clazz.getDeclaredConstructor(Context.class), clazz);
		init.invoke(instance, __context);
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
		removeKey(cls.getSimpleName().toLowerCase() + "Key", cls, newVal);
	}
	private static void removeKey(String name, Class<?> cls, Object newVal) {
		Key<Resolve>            key = HopeReflect.getAccess(cls, null, name);
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
