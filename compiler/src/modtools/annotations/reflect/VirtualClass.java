package modtools.annotations.reflect;

import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Scope.WriteableScope;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Type.*;
import com.sun.tools.javac.jvm.*;
import com.sun.tools.javac.jvm.Code.StackMapFormat;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import modtools.annotations.*;
import sun.reflect.annotation.ExceptionProxy;

import java.io.*;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.*;
import java.util.*;
import java.util.Map.Entry;

import static modtools.annotations.BaseProcessor.*;
import static modtools.annotations.PrintHelper.errs;

public class VirtualClass {
	public static byte[] defaultBytes;
	static {
		try {
			try (InputStream in = HopeReflect.class.getClassLoader()
			 .getResourceAsStream(NULL.class.getName().replace('.', '/') + ".class")) {
				VirtualClass.defaultBytes = in.readAllBytes();
			}
		} catch (IOException e) {
			errs(e);
			throw new RuntimeException(e);
		}
	}
	public static class NULL {}

	public static Class<?> mirrorType  = classOrNull("com.sun.tools.javac.model.AnnotationProxyMaker$MirroredTypeExceptionProxy");
	public static Class<?>                     mirrorTypes = classOrNull("com.sun.tools.javac.model.AnnotationProxyMaker$MirroredTypesExceptionProxy");
	/** 因为class不能完美复刻，所以这个表用于获取type */
	public static HashMap<Class<?>, ClassType> classToType = new HashMap<>();
	public static ClassLoader                  loader      = new ClassLoader(HopeReflect.class.getClassLoader()) {};
	public static Object defineMirrorClass(ExceptionProxy proxy) {
		if (!mirrorType.isInstance(proxy) && !mirrorTypes.isInstance(proxy))
			throw new IllegalArgumentException("type (" + proxy + ") isn't MirroredType(s)ExceptionProxy");
		if (mirrorTypes.isInstance(proxy)) {
			List<Type> types = HopeReflect.getAccess(mirrorTypes, proxy, "types");
			return types.stream().map(VirtualClass::defineMirrorType0)
			 .toList().toArray(Class[]::new);
		}
		return defineMirrorClass1(HopeReflect.getAccess(mirrorType, proxy, "type"));
	}
	public static Class<?> defineMirrorType0(Type type) {
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
			return Array.newInstance(defineMirrorType0(arrayType.elemtype), 0).getClass();
		}
		if (type.getTag() == TypeTag.NONE) return Object.class;
		throw new IllegalArgumentException("type: " + type + "(" + type.getClass() + "," + type.getTag() + ")");
	}
	public static Class<?> defineMirrorClass1(ClassType type) {
		Class<?> cl = classOrNull(type.tsym.flatName().toString(), loader);

		if (cl != null) return cl;
		cl = classToType.entrySet().stream().filter(e -> e.getValue() == type)
		 .map(Entry::getKey).findAny().orElse(null);
		if (cl != null) return cl;

		try {
			if (type.supertype_field != null && defineMirrorType0(type.supertype_field) == null) return null;
			if (type.interfaces_field != null && type.interfaces_field.stream()
			 .anyMatch(type1 -> defineMirrorType0(type1) == null)) return null;

			ClassSymbol symbol = new ClassSymbol(type.tsym.flags_field, type.tsym.name, type.tsym.owner);
			symbol.type.tsym = symbol;
			if (tryCreate(type, symbol)) return null;
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			// byte[] bytes = ObjectBytes;
			classWriter.writeClassFile(out, symbol);
			byte[] bytes = out.toByteArray();
			cl = defineHiddenClass(bytes);
			HopeReflect.setAccess(Class.class, cl, "name", type.toString());
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
			definer = HopeReflect.invoke(Lookup.class, HopeReflect.lookup, "makeHiddenClassDefiner",
			 new Object[]{null, bytes}, String.class, byte[].class);
		} catch (Throwable e) {
			Method definerM = Lookup.class.getDeclaredMethod("makeHiddenClassDefiner", String.class, byte[].class, Set.class,
			 Class.forName("jdk.internal.util.ClassFileDumper"));
			definerM.setAccessible(true);
			Object dumper = HopeReflect.getAccess(Lookup.class, null, "DEFAULT_DUMPER");
			definer = definerM.invoke(HopeReflect.lookup, null, bytes, Set.of(), dumper);
		}
		return HopeReflect.invoke(definer, "defineClass", new Object[]{true}, boolean.class);
	}
	private static boolean tryCreate(ClassType type, ClassSymbol symbol) {
		boolean valid = false;
		if (valid) for (Symbol sym : type.tsym.members().getSymbols()) {
			if (sym instanceof VarSymbol vs && defineMirrorType0(vs.type) == null) return true;
			if (sym instanceof MethodSymbol ms) {
				if (defineMirrorType0(ms.getReturnType()) == null) return true;
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
				selfType = HopeReflect.invoke(
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
			case VOID -> code.emitop0(ByteCodes.return_);
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
}
