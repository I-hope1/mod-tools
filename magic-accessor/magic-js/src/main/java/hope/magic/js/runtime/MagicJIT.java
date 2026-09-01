package hope.magic.js.runtime;

import hope.magic.runtime.Magic;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 HotSpot JVM 特权类 MagicAccessorImpl (MAGICIMPL) 的动态 JIT 存根生成器。
 * 绕过所有访问检查与 MethodHandle 包装，直发 invokespecial 硬件级字节码。
 */
public class MagicJIT implements Opcodes {

	private record InvokerKey(Class<?> clazz, String methodName, int arity, boolean isStatic) {}
	private record CtorKey(Class<?> clazz, int arity) {}
	private record MemberKey(Class<?> clazz, String memberName) {}
	private record PrimMemberKey(Class<?> clazz, String memberName, Class<?> primType) {}

	private static final AtomicLong COUNTER = new AtomicLong();
	private static final Map<InvokerKey, MagicInvoker> INVOKER_CACHE = new ConcurrentHashMap<>();
	private static final Map<CtorKey, MagicConstructorInvoker> CTOR_CACHE = new ConcurrentHashMap<>();
	private static final Map<Class<?>, MethodHandle> FN_ADAPTER_MH_CACHE = new ConcurrentHashMap<>();
	private static final Map<Class<?>, MethodHandle> OBJ_ADAPTER_MH_CACHE = new ConcurrentHashMap<>();

	@FunctionalInterface
	public interface MagicInvoker {
		Object invoke(Object target, Object[] args) throws Throwable;
	}

	@FunctionalInterface
	public interface MagicConstructorInvoker {
		Object newInstance(Object[] args) throws Throwable;
	}

	public static MagicInvoker getMethodInvoker(Class<?> clazz, String methodName, int arity, boolean isStatic) {
		InvokerKey key = new InvokerKey(clazz, methodName, arity, isStatic);
		return INVOKER_CACHE.computeIfAbsent(key, k -> createMethodInvoker(clazz, methodName, arity, isStatic));
	}

	public static MagicConstructorInvoker getConstructorInvoker(Class<?> clazz, int arity) {
		CtorKey key = new CtorKey(clazz, arity);
		return CTOR_CACHE.computeIfAbsent(key, k -> createConstructorInvoker(clazz, arity));
	}

	public static MagicInvoker createMethodInvoker(Class<?> clazz, String methodName, int arity, boolean isStatic) {
		Method targetMethod = MethodResolver.findMethod(clazz, methodName, arity, isStatic);
		if (targetMethod == null) return null;
		targetMethod.setAccessible(true);

		try {
			Magic.install();
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			String className = "hope/magic/gen/MagicInvoker_" + COUNTER.incrementAndGet();
			cw.visit(
				V1_8,
				ACC_PUBLIC | ACC_FINAL,
				className,
				null,
				"hope/magic/runtime/MAGICIMPL",
				new String[]{ "hope/magic/js/runtime/MagicJIT$MagicInvoker" }
			);

			// <init>
			createMagicInitMethod(cw);

			// Object invoke(Object target, Object[] args)
			MethodVisitor mv = cw.visitMethod(
				ACC_PUBLIC,
				"invoke",
				"(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
				null,
				new String[]{ "java/lang/Throwable" }
			);
			mv.visitCode();

			String targetOwner = Type.getInternalName(clazz);
			if (!isStatic) {
				mv.visitVarInsn(ALOAD, 1);
				mv.visitTypeInsn(CHECKCAST, targetOwner);
			}

			Class<?>[] paramTypes = targetMethod.getParameterTypes();
			for (int i = 0; i < paramTypes.length; i++) {
				mv.visitVarInsn(ALOAD, 2);
				pushInt(mv, i);
				mv.visitInsn(AALOAD);
				emitArgumentCast(mv, paramTypes[i]);
			}

			String methodDesc = Type.getMethodDescriptor(targetMethod);
			if (isStatic) {
				mv.visitMethodInsn(INVOKESTATIC, targetOwner, methodName, methodDesc, clazz.isInterface());
			} else if (clazz.isInterface()) {
				mv.visitMethodInsn(INVOKEINTERFACE, targetOwner, methodName, methodDesc, true);
			} else {
				mv.visitMethodInsn(INVOKEVIRTUAL, targetOwner, methodName, methodDesc, false);
			}

			emitReturnBox(mv, targetMethod.getReturnType());
			mv.visitInsn(ARETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();

			cw.visitEnd();
			byte[] bytes = cw.toByteArray();
			Class<?> genClass = Magic.defineClass(MagicJIT.class.getClassLoader(), bytes);
			return (MagicInvoker) genClass.getDeclaredConstructor().newInstance();
		} catch (Throwable e) {
			throw new RuntimeException("Failed to generate MagicInvoker for " + clazz.getName() + "#" + methodName, e);
		}
	}

	public static MagicConstructorInvoker createConstructorInvoker(Class<?> clazz, int arity) {
		Constructor<?> targetCtor = MethodResolver.findConstructor(clazz, arity);
		if (targetCtor == null) return null;
		targetCtor.setAccessible(true);

		try {
			Magic.install();
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			String className = "hope/magic/gen/MagicCtorInvoker_" + COUNTER.incrementAndGet();
			cw.visit(
				V1_8,
				ACC_PUBLIC | ACC_FINAL,
				className,
				null,
				"hope/magic/runtime/MAGICIMPL",
				new String[]{ "hope/magic/js/runtime/MagicJIT$MagicConstructorInvoker" }
			);

			// <init>
			createMagicInitMethod(cw);

			// Object newInstance(Object[] args)
			MethodVisitor mv = cw.visitMethod(
				ACC_PUBLIC,
				"newInstance",
				"([Ljava/lang/Object;)Ljava/lang/Object;",
				null,
				new String[]{ "java/lang/Throwable" }
			);
			mv.visitCode();

			String targetOwner = Type.getInternalName(clazz);
			mv.visitTypeInsn(NEW, targetOwner);
			mv.visitInsn(DUP);

			Class<?>[] paramTypes = targetCtor.getParameterTypes();
			for (int i = 0; i < paramTypes.length; i++) {
				mv.visitVarInsn(ALOAD, 1);
				pushInt(mv, i);
				mv.visitInsn(AALOAD);
				emitArgumentCast(mv, paramTypes[i]);
			}

			String ctorDesc = Type.getConstructorDescriptor(targetCtor);
			mv.visitMethodInsn(INVOKESPECIAL, targetOwner, "<init>", ctorDesc, false);
			mv.visitInsn(ARETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();

			cw.visitEnd();
			byte[] bytes = cw.toByteArray();
			Class<?> genClass = Magic.defineClass(MagicJIT.class.getClassLoader(), bytes);
			return (MagicConstructorInvoker) genClass.getDeclaredConstructor().newInstance();
		} catch (Throwable e) {
			throw new RuntimeException("Failed to generate MagicConstructorInvoker for " + clazz.getName(), e);
		}
	}
	private static void createMagicInitMethod(ClassWriter cw) {
		MethodVisitor initMv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
		initMv.visitCode();
		initMv.visitVarInsn(ALOAD, 0);
		initMv.visitMethodInsn(INVOKESPECIAL, "hope/magic/runtime/MAGICIMPL", "<init>", "()V", false);
		initMv.visitInsn(RETURN);
		initMv.visitMaxs(0, 0);
		initMv.visitEnd();
	}

	private static final Map<MemberKey, MethodHandle> GETTER_CACHE = new ConcurrentHashMap<>();
	private static final Map<MemberKey, MethodHandle> SETTER_CACHE = new ConcurrentHashMap<>();
	private static final Map<PrimMemberKey, MethodHandle> PRIMITIVE_GETTER_CACHE = new ConcurrentHashMap<>();

	public static MethodHandle getFieldGetterStub(Class<?> clazz, String fieldName) {
		MemberKey key = new MemberKey(clazz, fieldName);
		return GETTER_CACHE.computeIfAbsent(key, k -> createExactFieldGetterStub(clazz, fieldName));
	}

	public static MethodHandle getFieldSetterStub(Class<?> clazz, String fieldName) {
		MemberKey key = new MemberKey(clazz, fieldName);
		return SETTER_CACHE.computeIfAbsent(key, k -> createExactFieldSetterStub(clazz, fieldName));
	}

	public static MethodHandle getPrimitiveFieldGetterStub(Class<?> clazz, String fieldName, Class<?> primitiveType) {
		PrimMemberKey key = new PrimMemberKey(clazz, fieldName, primitiveType);
		return PRIMITIVE_GETTER_CACHE.computeIfAbsent(key, k -> createExactPrimitiveFieldGetterStub(clazz, fieldName, primitiveType));
	}

	public static MethodHandle createExactPrimitiveFieldGetterStub(Class<?> clazz, String fieldName, Class<?> primitiveType) {
		Field field = getDeclaredFieldRecursive(clazz, fieldName);
		if (field == null) return null;
		field.setAccessible(true);

		try {
			Magic.install();
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			String className = "hope/magic/gen/MagicPrimGetter_" + COUNTER.incrementAndGet();
			cw.visit(V1_8, ACC_PUBLIC | ACC_FINAL, className, null, "hope/magic/runtime/MAGICIMPL", null);

			// <init>
			createMagicInitMethod(cw);

			// public static <primitiveType> get(Object target)
			String primDesc = Type.getDescriptor(primitiveType);
			MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "get", "(Ljava/lang/Object;)" + primDesc, null, null);
			mv.visitCode();
			String owner = Type.getInternalName(field.getDeclaringClass());
			boolean isStatic = Modifier.isStatic(field.getModifiers());
			if (!isStatic) {
				mv.visitVarInsn(ALOAD, 0);
				mv.visitTypeInsn(CHECKCAST, owner);
				mv.visitFieldInsn(GETFIELD, owner, fieldName, Type.getDescriptor(field.getType()));
			} else {
				mv.visitFieldInsn(GETSTATIC, owner, fieldName, Type.getDescriptor(field.getType()));
			}

			// 原始类型精确强转
			Class<?> fType = field.getType();
			if (primitiveType == int.class) {
				if (fType == long.class) mv.visitInsn(L2I);
				else if (fType == double.class) mv.visitInsn(D2I);
				else if (fType == float.class) mv.visitInsn(F2I);
				mv.visitInsn(IRETURN);
			} else if (primitiveType == double.class) {
				if (fType == int.class || fType == short.class || fType == byte.class || fType == char.class) mv.visitInsn(I2D);
				else if (fType == long.class) mv.visitInsn(L2D);
				else if (fType == float.class) mv.visitInsn(F2D);
				mv.visitInsn(DRETURN);
			} else if (primitiveType == long.class) {
				if (fType == int.class || fType == short.class || fType == byte.class || fType == char.class) mv.visitInsn(I2L);
				else if (fType == double.class) mv.visitInsn(D2L);
				else if (fType == float.class) mv.visitInsn(F2L);
				mv.visitInsn(LRETURN);
			} else if (primitiveType == boolean.class) {
				mv.visitInsn(IRETURN);
			}

			mv.visitMaxs(0, 0);
			mv.visitEnd();

			cw.visitEnd();
			Class<?> genClass = Magic.defineClass(MagicJIT.class.getClassLoader(), cw.toByteArray());
			return Magic.lookup.findStatic(genClass, "get", MethodType.methodType(primitiveType, Object.class));
		} catch (Throwable e) {
			throw new RuntimeException("Failed to generate MagicPrimGetter for " + clazz.getName() + "." + fieldName, e);
		}
	}

	private static final Map<Method, MethodHandle> EXACT_METHOD_CACHE = new ConcurrentHashMap<>();

	public static MethodHandle createExactFieldGetterStub(Class<?> clazz, String fieldName) {
		MemberKey key = new MemberKey(clazz, fieldName);
		return GETTER_CACHE.computeIfAbsent(key, k -> generateExactFieldGetterStub(clazz, fieldName));
	}

	private static MethodHandle generateExactFieldGetterStub(Class<?> clazz, String fieldName) {
		Field field = getDeclaredFieldRecursive(clazz, fieldName);
		if (field == null) return null;
		field.setAccessible(true);

		try {
			Magic.install();
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			String className = "hope/magic/gen/MagicGetter_" + COUNTER.incrementAndGet();
			cw.visit(V1_8, ACC_PUBLIC | ACC_FINAL, className, null, "hope/magic/runtime/MAGICIMPL", null);

			// <init>
			createMagicInitMethod(cw);

			// public static Object get(Object target)
			MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "get", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
			mv.visitCode();
			String owner = Type.getInternalName(field.getDeclaringClass());
			boolean isStatic = Modifier.isStatic(field.getModifiers());
			if (!isStatic) {
				mv.visitVarInsn(ALOAD, 0);
				mv.visitTypeInsn(CHECKCAST, owner);
				mv.visitFieldInsn(GETFIELD, owner, fieldName, Type.getDescriptor(field.getType()));
			} else {
				mv.visitFieldInsn(GETSTATIC, owner, fieldName, Type.getDescriptor(field.getType()));
			}
			emitReturnBox(mv, field.getType());
			mv.visitInsn(ARETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();

			cw.visitEnd();
			Class<?> genClass = Magic.defineClass(MagicJIT.class.getClassLoader(), cw.toByteArray());
			return Magic.lookup.findStatic(genClass, "get", MethodType.methodType(Object.class, Object.class));
		} catch (Throwable e) {
			throw new RuntimeException("Failed to generate MagicGetter for " + clazz.getName() + "." + fieldName, e);
		}
	}

	public static MethodHandle createExactFieldSetterStub(Class<?> clazz, String fieldName) {
		MemberKey key = new MemberKey(clazz, fieldName);
		return SETTER_CACHE.computeIfAbsent(key, k -> generateExactFieldSetterStub(clazz, fieldName));
	}

	private static MethodHandle generateExactFieldSetterStub(Class<?> clazz, String fieldName) {
		Field field = getDeclaredFieldRecursive(clazz, fieldName);
		if (field == null) return null;
		field.setAccessible(true);

		try {
			Magic.install();
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			String className = "hope/magic/gen/MagicSetter_" + COUNTER.incrementAndGet();
			cw.visit(V1_8, ACC_PUBLIC | ACC_FINAL, className, null, "hope/magic/runtime/MAGICIMPL", null);

			// <init>
			createMagicInitMethod(cw);

			// public static void set(Object target, Object val)
			MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "set", "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
			mv.visitCode();
			String owner = Type.getInternalName(field.getDeclaringClass());
			boolean isStatic = Modifier.isStatic(field.getModifiers());
			if (!isStatic) {
				mv.visitVarInsn(ALOAD, 0);
				mv.visitTypeInsn(CHECKCAST, owner);
				mv.visitVarInsn(ALOAD, 1);
				emitArgumentCast(mv, field.getType());
				mv.visitFieldInsn(PUTFIELD, owner, fieldName, Type.getDescriptor(field.getType()));
			} else {
				mv.visitVarInsn(ALOAD, 1);
				emitArgumentCast(mv, field.getType());
				mv.visitFieldInsn(PUTSTATIC, owner, fieldName, Type.getDescriptor(field.getType()));
			}
			mv.visitInsn(RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();

			cw.visitEnd();
			Class<?> genClass = Magic.defineClass(MagicJIT.class.getClassLoader(), cw.toByteArray());
			return Magic.lookup.findStatic(genClass, "set", MethodType.methodType(void.class, Object.class, Object.class));
		} catch (Throwable e) {
			throw new RuntimeException("Failed to generate MagicSetter for " + clazz.getName() + "." + fieldName, e);
		}
	}

	public static MethodHandle createExactMethodStub(Class<?> clazz, Method targetMethod) {
		return EXACT_METHOD_CACHE.computeIfAbsent(targetMethod, m -> generateExactMethodStub(clazz, m));
	}

	private static MethodHandle generateExactMethodStub(Class<?> clazz, Method targetMethod) {
		int arity = targetMethod.getParameterCount();
		boolean isStatic = Modifier.isStatic(targetMethod.getModifiers());

		try {
			Magic.install();
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			String className = "hope/magic/gen/MagicExactMethod_" + COUNTER.incrementAndGet();
			cw.visit(V1_8, ACC_PUBLIC | ACC_FINAL, className, null, "hope/magic/runtime/MAGICIMPL", null);

			// <init>
			createMagicInitMethod(cw);

			Class<?>[] argTypes = new Class<?>[1 + arity];
			argTypes[0] = Object.class;
			for (int i = 0; i < arity; i++) argTypes[1 + i] = Object.class;
			MethodType stubType = MethodType.methodType(Object.class, argTypes);

			MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "invoke", stubType.toMethodDescriptorString(), null, null);
			mv.visitCode();
			String owner = Type.getInternalName(clazz);
			if (!isStatic) {
				mv.visitVarInsn(ALOAD, 0);
				mv.visitTypeInsn(CHECKCAST, owner);
			}

			Class<?>[] paramTypes = targetMethod.getParameterTypes();
			for (int i = 0; i < arity; i++) {
				mv.visitVarInsn(ALOAD, 1 + i);
				emitArgumentCast(mv, paramTypes[i]);
			}

			String methodDesc = Type.getMethodDescriptor(targetMethod);
			if (isStatic) {
				mv.visitMethodInsn(INVOKESTATIC, owner, targetMethod.getName(), methodDesc, clazz.isInterface());
			} else if (clazz.isInterface()) {
				mv.visitMethodInsn(INVOKEINTERFACE, owner, targetMethod.getName(), methodDesc, true);
			} else {
				mv.visitMethodInsn(INVOKEVIRTUAL, owner, targetMethod.getName(), methodDesc, false);
			}

			emitReturnBox(mv, targetMethod.getReturnType());
			mv.visitInsn(ARETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();

			cw.visitEnd();
			Class<?> genClass = Magic.defineClass(MagicJIT.class.getClassLoader(), cw.toByteArray());
			return Magic.lookup.findStatic(genClass, "invoke", stubType);
		} catch (Throwable e) {
			throw new RuntimeException("Failed to generate MagicExactMethod for " + clazz.getName() + "#" + targetMethod.getName(), e);
		}
	}

	private static Field getDeclaredFieldRecursive(Class<?> clazz, String fieldName) {
		Class<?> cur = clazz;
		while (cur != null && cur != Object.class) {
			try {
				return cur.getDeclaredField(fieldName);
			} catch (NoSuchFieldException ignored) {
				cur = cur.getSuperclass();
			}
		}
		return null;
	}

	private static Method findMatchingMethod(Class<?> clazz, String methodName, int arity) {
		return MethodResolver.findMethod(clazz, methodName, arity);
	}

	private static void pushInt(MethodVisitor mv, int val) {
		if (val >= -1 && val <= 5) {
			mv.visitInsn(ICONST_0 + val);
		} else if (val >= Byte.MIN_VALUE && val <= Byte.MAX_VALUE) {
			mv.visitIntInsn(BIPUSH, val);
		} else if (val >= Short.MIN_VALUE && val <= Short.MAX_VALUE) {
			mv.visitIntInsn(SIPUSH, val);
		} else {
			mv.visitLdcInsn(val);
		}
	}

	private static void emitArgumentCast(MethodVisitor mv, Class<?> pType) {
		if (pType == int.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toInt", "(Ljava/lang/Object;)I", false);
		} else if (pType == long.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toLong", "(Ljava/lang/Object;)J", false);
		} else if (pType == double.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toDouble", "(Ljava/lang/Object;)D", false);
		} else if (pType == float.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toFloat", "(Ljava/lang/Object;)F", false);
		} else if (pType == boolean.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toBoolean", "(Ljava/lang/Object;)Z", false);
		} else if (pType == short.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toShort", "(Ljava/lang/Object;)S", false);
		} else if (pType == byte.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toByte", "(Ljava/lang/Object;)B", false);
		} else if (pType == char.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toChar", "(Ljava/lang/Object;)C", false);
		} else if (pType == String.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toStr", "(Ljava/lang/Object;)Ljava/lang/String;", false);
		} else if (pType.isInterface() && pType != hope.magic.js.runtime.JSFunction.class && pType != hope.magic.js.runtime.JSObject.class) {
			mv.visitLdcInsn(Type.getType(pType));
			mv.visitInsn(SWAP);
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSLinker", "toInterface", "(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;", false);
			mv.visitTypeInsn(CHECKCAST, Type.getInternalName(pType));
		} else if (pType != Object.class) {
			mv.visitTypeInsn(CHECKCAST, Type.getInternalName(pType));
		}
	}

	private static void emitReturnBox(MethodVisitor mv, Class<?> retType) {
		if (retType == void.class) {
			mv.visitFieldInsn(GETSTATIC, "hope/magic/js/runtime/JSUndefined", "INSTANCE", "Lhope/magic/js/runtime/JSUndefined;");
		} else if (retType == int.class) {
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
		} else if (retType == long.class) {
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
		} else if (retType == double.class) {
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
		} else if (retType == float.class) {
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
		} else if (retType == boolean.class) {
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
		} else if (retType == short.class) {
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
		} else if (retType == byte.class) {
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
		} else if (retType == char.class) {
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
		}
	}

	// ==================== 动态接口适配器生成 (JIT Interface Adapters) ====================

	public static Object getFunctionAdapter(Class<?> targetType, JSFunction fn) {
		if (targetType == null || fn == null) return null;
		if (!targetType.isInterface()) return null;
		try {
			MethodHandle mh = FN_ADAPTER_MH_CACHE.computeIfAbsent(targetType, MagicJIT::createFunctionAdapterHandle);
			if (mh != null) {
				return mh.invoke(fn);
			}
		} catch (Throwable ignored) {
		}
		return createProxyFunctionAdapter(targetType, fn);
	}

	public static Object getObjectAdapter(Class<?> targetType, JSObject jsObj) {
		if (targetType == null || jsObj == null) return null;
		if (!targetType.isInterface()) return null;
		try {
			MethodHandle mh = OBJ_ADAPTER_MH_CACHE.computeIfAbsent(targetType, MagicJIT::createObjectAdapterHandle);
			if (mh != null) {
				return mh.invoke(jsObj);
			}
		} catch (Throwable ignored) {
		}
		return createProxyObjectAdapter(targetType, jsObj);
	}

	private static MethodHandle createFunctionAdapterHandle(Class<?> targetType) {
		Constructor<?> ctor = createFunctionAdapterConstructor(targetType);
		if (ctor == null) return null;
		try {
			return Magic.lookup.unreflectConstructor(ctor).asType(MethodType.methodType(Object.class, JSFunction.class));
		} catch (Throwable e) {
			return null;
		}
	}

	private static MethodHandle createObjectAdapterHandle(Class<?> targetType) {
		Constructor<?> ctor = createObjectAdapterConstructor(targetType);
		if (ctor == null) return null;
		try {
			return Magic.lookup.unreflectConstructor(ctor).asType(MethodType.methodType(Object.class, JSObject.class));
		} catch (Throwable e) {
			return null;
		}
	}

	private static Constructor<?> createFunctionAdapterConstructor(Class<?> targetType) {
		Method sam = JSLinker.getSingleAbstractMethod(targetType);
		if (sam == null) return null;

		try {
			Magic.install();
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			String className = "hope/magic/gen/JSFunctionAdapter_" + COUNTER.incrementAndGet();
			String targetOwner = Type.getInternalName(targetType);
			cw.visit(
				V1_8,
				ACC_PUBLIC | ACC_FINAL,
				className,
				null,
				"hope/magic/runtime/MAGICIMPL",
				new String[]{ targetOwner }
			);

			// public final JSFunction fn;
			cw.visitField(ACC_PUBLIC | ACC_FINAL, "fn", "Lhope/magic/js/runtime/JSFunction;", null, null).visitEnd();

			// <init>(JSFunction fn)
			MethodVisitor initMv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Lhope/magic/js/runtime/JSFunction;)V", null, null);
			initMv.visitCode();
			initMv.visitVarInsn(ALOAD, 0);
			initMv.visitMethodInsn(INVOKESPECIAL, "hope/magic/runtime/MAGICIMPL", "<init>", "()V", false);
			initMv.visitVarInsn(ALOAD, 0);
			initMv.visitVarInsn(ALOAD, 1);
			initMv.visitFieldInsn(PUTFIELD, className, "fn", "Lhope/magic/js/runtime/JSFunction;");
			initMv.visitInsn(RETURN);
			initMv.visitMaxs(0, 0);
			initMv.visitEnd();

			// SAM method
			emitAdapterSAMMethod(cw, className, sam);

			// Object methods
			emitAdapterObjectMethods(cw, className, "JSFunctionAdapter[" + targetType.getSimpleName() + "]");

			cw.visitEnd();
			byte[] bytes = cw.toByteArray();
			Class<?> genClass = Magic.defineClass(targetType.getClassLoader() != null ? targetType.getClassLoader() : MagicJIT.class.getClassLoader(), bytes);
			Constructor<?> ctor = genClass.getDeclaredConstructor(JSFunction.class);
			ctor.setAccessible(true);
			return ctor;
		} catch (Throwable e) {
			return null;
		}
	}

	/**
	 * 生成优化的 JSFunction 调用字节码。
	 * 根据参数个数选择特化的方法（call0/call1/call2/call3）或通用 call 方法。
	 * 假设 JSFunction 对象已在栈顶，context 和 thisObj 也已被压栈。
	 *
	 * @param mv 方法访问器
	 * @param paramTypes 参数类型数组
	 * @param retType 返回类型
	 */
	private static void emitOptimizedJSFunctionCall(MethodVisitor mv, Class<?>[] paramTypes, Class<?> retType) {
		if (paramTypes.length == 0) {
			mv.visitMethodInsn(INVOKEINTERFACE, "hope/magic/js/runtime/JSFunction", "call0", "(Lhope/magic/js/runtime/JSContext;Ljava/lang/Object;)Ljava/lang/Object;", true);
		} else if (paramTypes.length == 1) {
			emitLoadAndBox(mv, paramTypes[0], 1);
			mv.visitMethodInsn(INVOKEINTERFACE, "hope/magic/js/runtime/JSFunction", "call1", "(Lhope/magic/js/runtime/JSContext;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
		} else if (paramTypes.length == 2) {
			emitLoadAndBox(mv, paramTypes[0], 1);
			int slot2 = (paramTypes[0] == long.class || paramTypes[0] == double.class) ? 3 : 2;
			emitLoadAndBox(mv, paramTypes[1], slot2);
			mv.visitMethodInsn(INVOKEINTERFACE, "hope/magic/js/runtime/JSFunction", "call2", "(Lhope/magic/js/runtime/JSContext;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
		} else if (paramTypes.length == 3) {
			int slot = 1;
			for (int i = 0; i < 3; i++) {
				emitLoadAndBox(mv, paramTypes[i], slot);
				slot += (paramTypes[i] == long.class || paramTypes[i] == double.class) ? 2 : 1;
			}
			mv.visitMethodInsn(INVOKEINTERFACE, "hope/magic/js/runtime/JSFunction", "call3", "(Lhope/magic/js/runtime/JSContext;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
		} else {
			// >3 参数：构建 Object[] args
			pushInt(mv, paramTypes.length);
			mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");

			int localSlot = 1;
			for (int i = 0; i < paramTypes.length; i++) {
				Class<?> pt = paramTypes[i];
				mv.visitInsn(DUP);
				pushInt(mv, i);
				emitLoadAndBox(mv, pt, localSlot);
				localSlot += (pt == long.class || pt == double.class) ? 2 : 1;
				mv.visitInsn(AASTORE);
			}
			mv.visitMethodInsn(INVOKEINTERFACE, "hope/magic/js/runtime/JSFunction", "call", "(Lhope/magic/js/runtime/JSContext;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", true);
		}

		// 返回值拆箱 / 转换
		emitAdapterReturn(mv, retType);
	}

	private static void emitAdapterSAMMethod(ClassWriter cw, String className, Method sam) {
		String methodName = sam.getName();
		String methodDesc = Type.getMethodDescriptor(sam);
		Class<?>[] paramTypes = sam.getParameterTypes();
		Class<?> retType = sam.getReturnType();

		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, methodName, methodDesc, null, getExceptionNames(sam));
		mv.visitCode();

		// 1. 获取 fn
		mv.visitVarInsn(ALOAD, 0);
		mv.visitFieldInsn(GETFIELD, className, "fn", "Lhope/magic/js/runtime/JSFunction;");

		// 2. 参数压栈: cx, thisObj
		mv.visitInsn(ACONST_NULL); // cx
		mv.visitInsn(ACONST_NULL); // thisObj

		// 3. Zero-Allocation 特化直调 (call0, call1, call2, call3)
				emitOptimizedJSFunctionCall(mv, paramTypes, retType);

		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private static Constructor<?> createObjectAdapterConstructor(Class<?> targetType) {
		try {
			Magic.install();
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			String className = "hope/magic/gen/JSObjectAdapter_" + COUNTER.incrementAndGet();
			String targetOwner = Type.getInternalName(targetType);
			cw.visit(
				V1_8,
				ACC_PUBLIC | ACC_FINAL,
				className,
				null,
				"hope/magic/runtime/MAGICIMPL",
				new String[]{ targetOwner }
			);

			// public final JSObject jsObj;
			cw.visitField(ACC_PUBLIC | ACC_FINAL, "jsObj", "Lhope/magic/js/runtime/JSObject;", null, null).visitEnd();

			// <init>(JSObject jsObj)
			MethodVisitor initMv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Lhope/magic/js/runtime/JSObject;)V", null, null);
			initMv.visitCode();
			initMv.visitVarInsn(ALOAD, 0);
			initMv.visitMethodInsn(INVOKESPECIAL, "hope/magic/runtime/MAGICIMPL", "<init>", "()V", false);
			initMv.visitVarInsn(ALOAD, 0);
			initMv.visitVarInsn(ALOAD, 1);
			initMv.visitFieldInsn(PUTFIELD, className, "jsObj", "Lhope/magic/js/runtime/JSObject;");
			initMv.visitInsn(RETURN);
			initMv.visitMaxs(0, 0);
			initMv.visitEnd();

			// Implement all methods of interface
			for (Method m : targetType.getMethods()) {
				if (isObjectMethod(m)) continue;
				emitObjectAdapterMethod(cw, className, m);
			}

			// Object methods
			emitAdapterObjectMethods(cw, className, "JSObjectAdapter[" + targetType.getSimpleName() + "]");

			cw.visitEnd();
			byte[] bytes = cw.toByteArray();
			Class<?> genClass = Magic.defineClass(targetType.getClassLoader() != null ? targetType.getClassLoader() : MagicJIT.class.getClassLoader(), bytes);
			Constructor<?> ctor = genClass.getDeclaredConstructor(JSObject.class);
			ctor.setAccessible(true);
			return ctor;
		} catch (Throwable e) {
			return null;
		}
	}

	private static void emitObjectAdapterMethod(ClassWriter cw, String className, Method m) {
		String methodName = m.getName();
		String methodDesc = Type.getMethodDescriptor(m);
		Class<?>[] paramTypes = m.getParameterTypes();
		Class<?> retType = m.getReturnType();

		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, methodName, methodDesc, null, getExceptionNames(m));
		mv.visitCode();

		// 1. Object member = this.jsObj.get(propId);
		int propId = SymbolTable.id(methodName);
		mv.visitVarInsn(ALOAD, 0);
		mv.visitFieldInsn(GETFIELD, className, "jsObj", "Lhope/magic/js/runtime/JSObject;");
		pushInt(mv, propId);
		mv.visitMethodInsn(INVOKEVIRTUAL, "hope/magic/js/runtime/JSObject", "get", "(I)Ljava/lang/Object;", false);

		// Calculate slot for member
		int memberSlot = calcTotalParamSlots(paramTypes) + 1;
		mv.visitVarInsn(ASTORE, memberSlot);

		// 2. if (member instanceof JSFunction)
		mv.visitVarInsn(ALOAD, memberSlot);
		mv.visitTypeInsn(INSTANCEOF, "hope/magic/js/runtime/JSFunction");
		org.objectweb.asm.Label notFnLabel = new org.objectweb.asm.Label();
		mv.visitJumpInsn(IFEQ, notFnLabel);

		// member.call*(null, this.jsObj, ...)
		mv.visitVarInsn(ALOAD, memberSlot);
		mv.visitTypeInsn(CHECKCAST, "hope/magic/js/runtime/JSFunction");
		mv.visitInsn(ACONST_NULL); // cx
		mv.visitVarInsn(ALOAD, 0);
		mv.visitFieldInsn(GETFIELD, className, "jsObj", "Lhope/magic/js/runtime/JSObject;"); // thisObj = jsObj
		emitOptimizedJSFunctionCall(mv, paramTypes, retType);

		// 3. else if (member != JSUndefined.INSTANCE)
		mv.visitLabel(notFnLabel);
		mv.visitVarInsn(ALOAD, memberSlot);
		mv.visitFieldInsn(GETSTATIC, "hope/magic/js/runtime/JSUndefined", "INSTANCE", "Lhope/magic/js/runtime/JSUndefined;");
		org.objectweb.asm.Label undefLabel = new org.objectweb.asm.Label();
		mv.visitJumpInsn(IF_ACMPEQ, undefLabel);

		mv.visitVarInsn(ALOAD, memberSlot);
		emitAdapterReturn(mv, retType);

		// 4. Default return
		mv.visitLabel(undefLabel);
		emitDefaultReturn(mv, retType);

		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private static int calcTotalParamSlots(Class<?>[] paramTypes) {
		int count = 0;
		for (Class<?> p : paramTypes) {
			count += (p == long.class || p == double.class) ? 2 : 1;
		}
		return count;
	}

	private static void emitLoadAndBox(MethodVisitor mv, Class<?> pt, int localSlot) {
		if (pt == int.class) {
			mv.visitVarInsn(ILOAD, localSlot);
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
		} else if (pt == long.class) {
			mv.visitVarInsn(LLOAD, localSlot);
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
		} else if (pt == double.class) {
			mv.visitVarInsn(DLOAD, localSlot);
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
		} else if (pt == float.class) {
			mv.visitVarInsn(FLOAD, localSlot);
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
		} else if (pt == boolean.class) {
			mv.visitVarInsn(ILOAD, localSlot);
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
		} else if (pt == byte.class) {
			mv.visitVarInsn(ILOAD, localSlot);
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
		} else if (pt == short.class) {
			mv.visitVarInsn(ILOAD, localSlot);
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
		} else if (pt == char.class) {
			mv.visitVarInsn(ILOAD, localSlot);
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
		} else {
			mv.visitVarInsn(ALOAD, localSlot);
		}
	}

	private static void emitAdapterReturn(MethodVisitor mv, Class<?> retType) {
		if (retType == void.class) {
			mv.visitInsn(POP);
			mv.visitInsn(RETURN);
		} else if (retType == int.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toInt", "(Ljava/lang/Object;)I", false);
			mv.visitInsn(IRETURN);
		} else if (retType == long.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toLong", "(Ljava/lang/Object;)J", false);
			mv.visitInsn(LRETURN);
		} else if (retType == double.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toDouble", "(Ljava/lang/Object;)D", false);
			mv.visitInsn(DRETURN);
		} else if (retType == float.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toDouble", "(Ljava/lang/Object;)D", false);
			mv.visitInsn(D2F);
			mv.visitInsn(FRETURN);
		} else if (retType == boolean.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "isTruthy", "(Ljava/lang/Object;)Z", false);
			mv.visitInsn(IRETURN);
		} else if (retType == short.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toInt", "(Ljava/lang/Object;)I", false);
			mv.visitInsn(I2S);
			mv.visitInsn(IRETURN);
		} else if (retType == byte.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toInt", "(Ljava/lang/Object;)I", false);
			mv.visitInsn(I2B);
			mv.visitInsn(IRETURN);
		} else if (retType == char.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSLinker", "toChar", "(Ljava/lang/Object;)C", false);
			mv.visitInsn(IRETURN);
		} else if (retType == String.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toStr", "(Ljava/lang/Object;)Ljava/lang/String;", false);
			mv.visitInsn(ARETURN);
		} else {
			mv.visitLdcInsn(Type.getType(retType));
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSLinker", "castValue", "(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;", false);
			mv.visitTypeInsn(CHECKCAST, Type.getInternalName(retType));
			mv.visitInsn(ARETURN);
		}
	}

	private static void emitDefaultReturn(MethodVisitor mv, Class<?> retType) {
		if (retType == void.class) {
			mv.visitInsn(RETURN);
		} else if (retType == int.class || retType == boolean.class || retType == byte.class || retType == short.class || retType == char.class) {
			mv.visitInsn(ICONST_0);
			mv.visitInsn(IRETURN);
		} else if (retType == long.class) {
			mv.visitInsn(LCONST_0);
			mv.visitInsn(LRETURN);
		} else if (retType == float.class) {
			mv.visitInsn(FCONST_0);
			mv.visitInsn(FRETURN);
		} else if (retType == double.class) {
			mv.visitInsn(DCONST_0);
			mv.visitInsn(DRETURN);
		} else {
			mv.visitInsn(ACONST_NULL);
			mv.visitInsn(ARETURN);
		}
	}

	private static void emitAdapterObjectMethods(ClassWriter cw, String className, String toStringText) {
		// toString()
		MethodVisitor tsMv = cw.visitMethod(ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
		tsMv.visitCode();
		tsMv.visitLdcInsn(toStringText);
		tsMv.visitInsn(ARETURN);
		tsMv.visitMaxs(0, 0);
		tsMv.visitEnd();

		// hashCode()
		MethodVisitor hcMv = cw.visitMethod(ACC_PUBLIC, "hashCode", "()I", null, null);
		hcMv.visitCode();
		hcMv.visitVarInsn(ALOAD, 0);
		hcMv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "identityHashCode", "(Ljava/lang/Object;)I", false);
		hcMv.visitInsn(IRETURN);
		hcMv.visitMaxs(0, 0);
		hcMv.visitEnd();

		// equals(Object)
		MethodVisitor eqMv = cw.visitMethod(ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null);
		eqMv.visitCode();
		eqMv.visitVarInsn(ALOAD, 0);
		eqMv.visitVarInsn(ALOAD, 1);
		org.objectweb.asm.Label notEq = new org.objectweb.asm.Label();
		eqMv.visitJumpInsn(IF_ACMPNE, notEq);
		eqMv.visitInsn(ICONST_1);
		eqMv.visitInsn(IRETURN);
		eqMv.visitLabel(notEq);
		eqMv.visitInsn(ICONST_0);
		eqMv.visitInsn(IRETURN);
		eqMv.visitMaxs(0, 0);
		eqMv.visitEnd();
	}

	private static boolean isObjectMethod(Method m) {
		String name = m.getName();
		Class<?>[] params = m.getParameterTypes();
		if ("equals".equals(name) && params.length == 1 && params[0] == Object.class) return true;
		if ("hashCode".equals(name) && params.length == 0) return true;
		if ("toString".equals(name) && params.length == 0) return true;
		return false;
	}

	private static String[] getExceptionNames(Method m) {
		Class<?>[] ex = m.getExceptionTypes();
		if (ex.length == 0) return null;
		String[] names = new String[ex.length];
		for (int i = 0; i < ex.length; i++) {
			names[i] = Type.getInternalName(ex[i]);
		}
		return names;
	}

	public static Object createProxyFunctionAdapter(Class<?> targetType, JSFunction fn) {
		ClassLoader cl = targetType.getClassLoader() != null ? targetType.getClassLoader() : MagicJIT.class.getClassLoader();
		return java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{ targetType }, (proxy, method, methodArgs) -> {
			if (method.getDeclaringClass() == Object.class) {
				String name = method.getName();
				switch (name) {
					case "toString" -> { return "JSFunctionAdapter[" + targetType.getSimpleName() + "]"; }
					case "hashCode" -> { return System.identityHashCode(proxy); }
					case "equals" -> { return proxy == (methodArgs != null && methodArgs.length > 0 ? methodArgs[0] : null); }
				}
			}
			Object[] safeArgs = methodArgs == null ? new Object[0] : methodArgs;
			Object result = fn.call(null, null, safeArgs);
			Class<?> retType = method.getReturnType();
			if (retType == void.class) return null;
			return JSLinker.castValue(result, retType);
		});
	}

	public static Object createProxyObjectAdapter(Class<?> targetType, JSObject jsObj) {
		ClassLoader cl = targetType.getClassLoader() != null ? targetType.getClassLoader() : MagicJIT.class.getClassLoader();
		return java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{ targetType }, (proxy, method, methodArgs) -> {
			if (method.getDeclaringClass() == Object.class) {
				String name = method.getName();
				switch (name) {
					case "toString" -> { return "JSObjectAdapter[" + targetType.getSimpleName() + "]"; }
					case "hashCode" -> { return System.identityHashCode(proxy); }
					case "equals" -> { return proxy == (methodArgs != null && methodArgs.length > 0 ? methodArgs[0] : null); }
				}
			}
			String methodName = method.getName();
			Object member = jsObj.get(methodName);
			if (member instanceof JSFunction fn) {
				Object[] safeArgs = methodArgs == null ? new Object[0] : methodArgs;
				Object result = fn.call(null, jsObj, safeArgs);
				Class<?> retType = method.getReturnType();
				if (retType == void.class) return null;
				return JSLinker.castValue(result, retType);
			}
			if (method.isDefault()) {
				return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, methodArgs);
			}
			if (member != null && member != JSUndefined.INSTANCE) {
				return JSLinker.castValue(member, method.getReturnType());
			}
			Class<?> retType = method.getReturnType();
			if (retType == void.class) return null;
			return JSLinker.castValue(null, retType);
		});
	}
}
