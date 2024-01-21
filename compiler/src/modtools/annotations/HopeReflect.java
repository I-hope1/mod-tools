package modtools.annotations;

import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Scope.WriteableScope;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Type.*;
import com.sun.tools.javac.jvm.*;
import com.sun.tools.javac.jvm.Code.StackMapFormat;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.TreeMaker;
import sun.misc.Unsafe;
import sun.reflect.ReflectionFactory;
import sun.reflect.annotation.ExceptionProxy;

import java.io.*;
import java.lang.invoke.*;
import java.lang.invoke.MethodType;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.*;
import java.util.*;
import java.util.Map.Entry;

import static modtools.annotations.BaseProcessor.*;

public class HopeReflect {
	public static Unsafe unsafe = getUnsafe();
	public static Lookup lookup = getLookup();

	static {
		try {
			Field  f      = Class.class.getDeclaredField("module");
			long   off    = unsafe.objectFieldOffset(f);
			Module module = Object.class.getModule();
			unsafe.putObject(BaseProcessor.class, off, module);
			unsafe.putObject(HopeReflect.class, off, module);
			unsafe.putObject(AccessSetter.class, off, module);
			// unsafe.putObject(Reflect.class, off, module);
			Class<?> reflect = Class.forName("jdk.internal.reflect.Reflection");
			Map      map     = (Map) lookup.findStaticGetter(reflect, "fieldFilterMap", Map.class).invokeExact();
			if (map != null) map.clear();
			map = (Map) lookup.findStaticGetter(reflect, "methodFilterMap", Map.class).invokeExact();
			if (map != null) map.clear();

			module();
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	private static Unsafe getUnsafe() {
		try {
			Field f = Unsafe.class.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			return (Unsafe) f.get(null);
		} catch (Exception e) {throw new RuntimeException(e);}
	}
	private static Lookup getLookup() {
		try {
			Lookup lookup = (Lookup) ReflectionFactory.getReflectionFactory().newConstructorForSerialization(
			 Lookup.class, Lookup.class.getDeclaredConstructor(Class.class)
			).newInstance(Lookup.class);
			lookup = (Lookup) lookup.findStaticVarHandle(Lookup.class, "IMPL_LOOKUP", Lookup.class).get();
			return lookup;
		} catch (Exception e) {throw new RuntimeException(e);}
	}
	public static void module() throws Throwable {
		MethodHandle OPEN_MODULE = lookup.findVirtual(Module.class, "implAddOpens", MethodType.methodType(Void.TYPE, String.class));
		OPEN_MODULE.invokeExact(Object.class.getModule(), "jdk.internal.module");
		OPEN_MODULE.invokeExact(Object.class.getModule(), "jdk.internal.misc");
		OPEN_MODULE.invokeExact(Object.class.getModule(), "sun.reflect.annotation");
		OPEN_MODULE.invokeExact(Object.class.getModule(), "jdk.internal.access");
		OPEN_MODULE.invokeExact(TreeMaker.class.getModule(), "com.sun.tools.javac.model");
		OPEN_MODULE.invokeExact(TreeMaker.class.getModule(), "com.sun.tools.javac.jvm");
		OPEN_MODULE.invokeExact(TreeMaker.class.getModule(), "com.sun.tools.javac.comp");
		OPEN_MODULE.invokeExact(TreeMaker.class.getModule(), "com.sun.tools.javac.main");
		// Modules.addOpens(AttributeTree.class.getModule(), "", MyReflect.class.getModule());
	}
	public static byte[] ObjectBytes;

	static {
		try {
			ObjectBytes = HopeReflect.class.getClassLoader()
			 .getResourceAsStream(NULL.class.getName().replace('.', '/') + ".class").readAllBytes();
		} catch (IOException e) {
			PrintHelper.errs(e);
			throw new RuntimeException(e);
		}
	}

	public static Class<?> mirrorType  = classOrNull("com.sun.tools.javac.model.AnnotationProxyMaker$MirroredTypeExceptionProxy");
	public static Class<?> mirrorTypes = classOrNull("com.sun.tools.javac.model.AnnotationProxyMaker$MirroredTypesExceptionProxy");

	/** 因为class不能完美复刻，所以这个表用于获取type */
	public static HashMap<Class<?>, ClassType> classToType = new HashMap<>();

	// ---------------------定义类------------------------


	public static Object defineMirrorClass(ExceptionProxy proxy) {
		if (!mirrorType.isInstance(proxy) && !mirrorTypes.isInstance(proxy))
			throw new IllegalArgumentException("type (" + proxy + ") isn't MirroredType(s)ExceptionProxy");
		if (mirrorTypes.isInstance(proxy)) {
			List<Type> types = getAccess(mirrorTypes, proxy, "types");
			return types.stream().map(HopeReflect::defineMirrorClass0)
			 .toList().toArray(new Class[0]);
		}
		return defineMirrorClass1(getAccess(mirrorType, proxy, "type"));
	}
	public static Class<?> defineMirrorClass0(Type type) {
		if (type instanceof ClassType ct) {
			return defineMirrorClass1(ct);
		}
		if (type.isPrimitiveOrVoid())
			return switch (type.getTag()) {
				case BYTE -> byte.class;
				case CHAR -> char.class;
				case SHORT -> short.class;
				case LONG -> long.class;
				case FLOAT -> float.class;
				case INT -> int.class;
				case DOUBLE -> double.class;
				case BOOLEAN -> boolean.class;
				case VOID -> void.class;
				default -> throw new IllegalArgumentException("type: " + type);
			};
		if (type instanceof ArrayType arrayType) {
			return Array.newInstance(defineMirrorClass0(arrayType.elemtype), 0).getClass();
		}
		if (type.getTag() == TypeTag.NONE) return Object.class;
		throw new IllegalArgumentException("type: " + type + "(" + type.getClass() + "," + type.getTag() + ")");
	}

	public static Class<?> defineMirrorClass1(ClassType type) {
		return defineMirrorClass0(type);
	}

	public static ClassLoader loader = new ClassLoader(HopeReflect.class.getClassLoader()) {};

	private static Class<?> defineMirrorClass0(ClassType type) {
		Class<?> cl = classOrNull(type.tsym.flatName().toString(), loader);
		// Log.info("t: @, @", type.tsym.flatName(), cl);

		if (cl != null) return cl;
		cl = classToType.entrySet().stream().filter(e -> e.getValue() == type)
		 .map(Entry::getKey).findAny().orElse(null);
		if (cl != null) return cl;

		try {
			if (type.supertype_field != null && defineMirrorClass0(type.supertype_field) == null) return null;
			if (type.interfaces_field != null && type.interfaces_field.stream()
			 .anyMatch(type1 -> defineMirrorClass0(type1) == null)) return null;

			ClassSymbol symbol = new ClassSymbol(type.tsym.flags_field, type.tsym.name, type.tsym.owner);
			symbol.type.tsym = symbol;
			// __gen__.genClass(Enter.instance(context).getEnv(type.tsym), (JCClassDecl) trees.getTree(type.tsym));
			if (extracted(type, symbol)) return null;
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			// byte[] bytes = ObjectBytes;
			classWriter.writeClassFile(out, symbol);
			byte[] bytes = out.toByteArray();
			cl = defineHiddenClass(bytes);
			setAccess(Class.class, cl, "name", type.toString());
			// cl = jdk.internal.misc.Unsafe.getUnsafe().defineClass(
			//  null, bytes, 0, bytes.length, loader, null);
			// if (cl != null) classToType.put(cl, type);
			return cl;
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}
	public static Class<?> defineHiddenClass(byte[] bytes) throws Throwable {
		// if (true) return jdk.internal.misc.Unsafe.getUnsafe().defineClass(null, bytes, 0, bytes.length, loader, null);
			Object definer;
		try {
			definer = invoke(Lookup.class, lookup, "makeHiddenClassDefiner",
			 new Object[]{null, bytes}, String.class, byte[].class);
		} catch (Throwable e) {
			Method definerM = Lookup.class.getDeclaredMethod("makeHiddenClassDefiner", String.class, byte[].class, Set.class,
			 Class.forName("jdk.internal.util.ClassFileDumper"));
			definerM.setAccessible(true);
			var dumper = getAccess(Lookup.class, null, "DEFAULT_DUMPER");
			definer = definerM.invoke(lookup, null, bytes, Set.of(), dumper);
		}
		return invoke(definer, "defineClass", new Object[]{true}, boolean.class);
	}
	public static <T> T invoke(Object object, String name, Object[] args, Class<?>... parameterTypes) {
		return invoke(object.getClass(), object, name, args, parameterTypes);
	}
	public static <T> T invoke(Class<?> type, Object object, String name, Object[] args, Class<?>... parameterTypes) {
		try {
			Method method = type.getDeclaredMethod(name, parameterTypes);
			method.setAccessible(true);
			return (T) method.invoke(object, args);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static boolean extracted(ClassType type, ClassSymbol symbol) {
		boolean valid = false;
		if (valid) for (Symbol sym : type.tsym.members().getSymbols()) {
			if (sym instanceof VarSymbol vs && defineMirrorClass0(vs.type) == null) return true;
			if (sym instanceof MethodSymbol ms) {
				if (defineMirrorClass0(ms.getReturnType()) == null) return true;
				// if (ms.params.stream().anyMatch(
				//  type1 -> type1.type.getTag() != TypeTag.TYPEVAR
				// 					&& defineMirrorClass0(type1.type) == null)) return null;
				TreePath path = trees.getPath(sym);
				if (path == null) {
					valid = false;
					break;
				}
				JCCompilationUnit unit = (JCCompilationUnit) path.getCompilationUnit();
				generateCode(ms, unit);
			}
		}
		symbol.members_field =
		 valid ? WriteableScope.create(symbol) : type.tsym.members();
		return false;
	}
	private static void generateCode(MethodSymbol ms, JCCompilationUnit unit) {
		Code code = ms.code = new Code(ms, true, unit.getLineMap(),
		 false, StackMapFormat.CLDC,
		 false, null, mSymtab, types, new PoolWriter(types, names));
		if (!ms.isStatic()) {
			Type    selfType            = ms.owner.type;
			boolean generateConstructor = ms.isConstructor() && selfType != mSymtab.objectType;
			if (generateConstructor) {
				selfType = invoke(
				 classOrThrow("com.sun.tools.javac.jvm.UninitializedType"),
				 "uninitializedThis", new Object[]{selfType}, Type.class);
			}
			code.setDefined(code.newLocal(new VarSymbol(Modifier.FINAL, names._this, selfType, ms.owner)));
			if (generateConstructor) {
				code.emitop0(ByteCodes.aload_0);
				Symbol member = mSymtab.objectType.tsym.members().findFirst(names.init);
				code.emitInvokespecial(member, ms.type);
			}
		}
		for (var l = ms.params; l.nonEmpty(); l = l.tail) {
			code.setDefined(code.newLocal(l.head));
		}
		switch (ms.type.getReturnType().getTag()) {
			case VOID -> {
				code.emitop0(ByteCodes.return_);
			}
			case INT, BYTE, CHAR, SHORT, BOOLEAN -> {
				code.emitop0(ByteCodes.iconst_0);
				code.emitop0(ByteCodes.ireturn);
			}
			case FLOAT -> {
				code.emitop0(ByteCodes.fconst_0);
				code.emitop0(ByteCodes.freturn);
			}
			case LONG -> {
				code.emitop0(ByteCodes.lconst_0);
				code.emitop0(ByteCodes.lreturn);
			}
			case DOUBLE -> {
				code.emitop0(ByteCodes.dconst_0);
				code.emitop0(ByteCodes.dreturn);
			}
			default -> {
				code.emitop0(ByteCodes.aconst_null);
				code.emitop0(ByteCodes.areturn);
			}
		}

		code.entryPoint();
	}
	public static <T> T getAccess(Class<?> cls, Object obj, String name) {
		try {
			Field field = cls.getDeclaredField(name);
			field.setAccessible(true);
			return (T) field.get(obj);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	public static <T> T get(Object obj, String name) {
		try {
			Field field = obj.getClass().getField(name);
			field.setAccessible(true);
			return (T) field.get(obj);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	public static void setAccess(Class<?> clazz, Object obj, String name, Object value) {
		try {
			Field field = clazz.getDeclaredField(name);
			unsafe.putObject(obj, unsafe.objectFieldOffset(field), value);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	public static void set(Class<?> clazz, Object obj, String name, Object value) {
		try {
			Field field = clazz.getField(name);
			unsafe.putObject(obj, unsafe.objectFieldOffset(field), value);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	public static class NULL {}
	static class AccessSetter {
		public static void setAccess(AccessibleObject obj) {
			obj.setAccessible(true);
		}
	}
}
