package hope.magic.js.runtime;

import hope.magic.runtime.Magic;
import org.objectweb.asm.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.objectweb.asm.Opcodes.*;

/**
 * 动态 Java 类扩展器 (Java Class Extender)。
 * 核心特性：
 * 1. 【单父类唯一定义】：对每个 Java 父类全局仅生成 1 个子类字节码类（通过 ClassValue 缓存，Metaspace 零膨胀）。
 * 2. 【位掩码快速短路】：通过 __magic_override_mask 单指令快速短路未覆写的方法，性能直通原生 super。
 * 3. 【双向透明互操作】：生成的实例即是标准 Java 父类实例，又通过 JSBridgedObject 承载动态 JS 原型与属性。
 * 4. 【super 桥接机制】：生成 __magic_super_xxx 方法供 JS 侧快速显式调用父类原生逻辑。
 */
public final class JavaClassExtender {

	private static final AtomicLong ID_GEN = new AtomicLong(0);

	public static class ClassInfo {
		public final Class<?> targetClass;
		public final Class<?> subClass;
		public final Map<String, Long> methodMasks; // 方法名 -> 掩码位
		public final List<Constructor<?>> constructors; // 原父类可见构造器列表
		public final List<Constructor<?>> subConstructors; // 子类构造器列表
		public final Constructor<?> noArgSubConstructor; // 快速无参构造器 (JSObject, long)
		public final java.lang.invoke.MethodHandle noArgSubMh; // 快速无参构造器 MethodHandle

		public ClassInfo(Class<?> targetClass, Class<?> subClass,
						 Map<String, Long> methodMasks, List<Constructor<?>> constructors,
						 List<Constructor<?>> subConstructors) {
			this.targetClass = targetClass;
			this.subClass = subClass;
			this.methodMasks = methodMasks;
			this.constructors = constructors;
			this.subConstructors = subConstructors;
			Constructor<?> noArg = null;
			java.lang.invoke.MethodHandle mh = null;
			for (Constructor<?> c : subConstructors) {
				Class<?>[] pTypes = c.getParameterTypes();
				if (pTypes.length == 2 && pTypes[0] == JSObject.class && pTypes[1] == long.class) {
					noArg = c;
					try {
						mh = Magic.lookup.unreflectConstructor(c);
					} catch (Throwable ignored) {}
					break;
				}
			}
			this.noArgSubConstructor = noArg;
			this.noArgSubMh = mh;
		}
	}

	private static final ClassValue<ClassInfo> SUBCLASS_CACHE = new ClassValue<>() {
		@Override
		protected ClassInfo computeValue(Class<?> type) {
			return generateSubclass(type);
		}
	};

	public static class JSFunctionObject extends JSObject implements JSFunction {
		private final JSFunction fn;

		public JSFunctionObject(JSFunction fn) {
			this.fn = fn;
		}

		public JSFunctionObject(JSObject prototype, JSFunction fn) {
			super(prototype);
			this.fn = fn;
		}

		@Override
		public Object call(JSContext cx, Object thisObj, Object[] args) throws Throwable {
			return fn.call(cx, thisObj, args);
		}
	}

	public static ClassInfo getClassInfo(Class<?> superClass) {
		return SUBCLASS_CACHE.get(superClass);
	}

	public static Class<?> getJavaSuperClass(Object obj) {
		if (obj instanceof Class<?> c) return c;
		if (obj instanceof JSObject jsObj) {
			Object jc = jsObj.get("__magic_java_class__");
			if (jc instanceof Class<?> c) return c;
		}
		return null;
	}

	public static JSFunction defineClass(
			JSContext cx,
			Object superClassObj,
			String className,
			Object[] methodPairs,
			Object[] staticMethodPairs,
			JSFunction ctorFn
	) {
		Map<String, JSFunction> methods = new LinkedHashMap<>();
		if (methodPairs != null) {
			for (int i = 0; i < methodPairs.length; i += 2) {
				methods.put((String) methodPairs[i], (JSFunction) methodPairs[i + 1]);
			}
		}

		Map<String, JSFunction> staticMethods = new LinkedHashMap<>();
		if (staticMethodPairs != null) {
			for (int i = 0; i < staticMethodPairs.length; i += 2) {
				staticMethods.put((String) staticMethodPairs[i], (JSFunction) staticMethodPairs[i + 1]);
			}
		}

		JSFunction resultCtor;
		Class<?> javaSuperClass = getJavaSuperClass(superClassObj);
		if (javaSuperClass != null) {
			JSFunction superJSClass = (superClassObj instanceof JSFunction sf) ? sf : null;
			resultCtor = createClassConstructor(javaSuperClass, superJSClass, methods, ctorFn);
		} else if (superClassObj instanceof JSFunction superCtor) {
			resultCtor = createJSClassConstructor(cx, superCtor, methods, ctorFn);
		} else if (superClassObj == null || superClassObj == JSUndefined.INSTANCE) {
			resultCtor = createJSClassConstructor(cx, null, methods, ctorFn);
		} else {
			throw new RuntimeException("TypeError: Superclass must be a Java class or JS constructor function, got " + superClassObj);
		}

		if (resultCtor instanceof JSObject ctorObj) {
			for (Map.Entry<String, JSFunction> entry : staticMethods.entrySet()) {
				ctorObj.put(entry.getKey(), entry.getValue());
			}
		}

		return resultCtor;
	}

	private static JSFunction createJSClassConstructor(
			JSContext cx,
			JSFunction superCtor,
			Map<String, JSFunction> methods,
			JSFunction jsCtor
	) {
		JSObject proto;
		if (superCtor instanceof JSObject superCtorObj) {
			Object superProto = superCtorObj.get("prototype");
			proto = new JSObject(superProto instanceof JSObject sp ? sp : JSContext.LazyObject.OBJECT_PROTOTYPE);
		} else {
			proto = new JSObject();
		}

		for (Map.Entry<String, JSFunction> entry : methods.entrySet()) {
			proto.put(entry.getKey(), entry.getValue());
		}

		JSFunctionObject ctor = new JSFunctionObject((callCx, thisObj, args) -> {
			JSObject inst;
			if (thisObj instanceof JSObject existing) {
				inst = existing;
			} else {
				inst = new JSObject(proto);
			}
			if (jsCtor != null) {
				jsCtor.call(callCx, inst, args != null ? args : new Object[0]);
			} else if (superCtor != null) {
				superCtor.call(callCx, inst, args != null ? args : new Object[0]);
			}
			return inst;
		});

		ctor.put("prototype", proto);
		proto.put("constructor", ctor);
		if (superCtor instanceof JSObject superCtorObj) {
			ctor.setPrototype(superCtorObj);
		}
		return ctor;
	}

	public static JSFunction createClassConstructor(
			Class<?> javaSuperClass,
			JSFunction superJSClass,
			Map<String, JSFunction> methods,
			JSFunction jsCtor
	) {
		ClassInfo info = getClassInfo(javaSuperClass);

		// 计算掩码 (继承父 JS 类的掩码)
		long mask = 0L;
		if (superJSClass instanceof JSObject superObj) {
			Object superMask = superObj.get("__magic_override_mask__");
			if (superMask instanceof Long m) {
				mask |= m;
			} else if (superMask instanceof Number num) {
				mask |= num.longValue();
			}
		}
		if (methods != null) {
			for (String methodName : methods.keySet()) {
				Long shift = info.methodMasks.get(methodName);
				if (shift != null && shift < 64) {
					mask |= (1L << shift);
				}
			}
		}
		final long finalMask = mask;

		JSObject proto;
		if (superJSClass instanceof JSObject superCtorObj) {
			Object superProto = superCtorObj.get("prototype");
			proto = new JSObject(superProto instanceof JSObject sp ? sp : JSContext.LazyObject.OBJECT_PROTOTYPE);
		} else {
			proto = new JSObject();
		}

		if (methods != null) {
			for (Map.Entry<String, JSFunction> entry : methods.entrySet()) {
				proto.put(entry.getKey(), entry.getValue());
			}
		}

		JSFunctionObject ctor = new JSFunctionObject((cx, thisObj, args) -> {
			Object[] callArgs = args != null ? args : new Object[0];
			Object instance;
			if (thisObj instanceof JSBridgedObject existing) {
				instance = existing;
			} else {
				JSObject jsObj = new JSObject(proto);
				instance = instantiateSubclass(info, jsObj, finalMask, callArgs);
			}

			// 执行 JS constructor (若存在)
			if (jsCtor != null) {
				jsCtor.call(cx, instance, callArgs);
			} else if (superJSClass != null) {
				superJSClass.call(cx, instance, callArgs);
			}

			return instance;
		});

		ctor.put("prototype", proto);
		proto.put("constructor", ctor);
		ctor.put("__magic_java_class__", javaSuperClass);
		ctor.put("__magic_override_mask__", finalMask);
		if (superJSClass instanceof JSObject superCtorObj) {
			ctor.setPrototype(superCtorObj);
		}
		return ctor;
	}

	public static JSFunction createClassConstructor(Class<?> superClass, Map<String, JSFunction> methods, JSFunction jsCtor) {
		return createClassConstructor(superClass, null, methods, jsCtor);
	}

	private static boolean isTypeCompatible(Object arg, Class<?> targetType) {
		if (arg == null) {
			return !targetType.isPrimitive();
		}
		if (targetType.isInstance(arg)) {
			return true;
		}
		if (targetType == int.class || targetType == Integer.class ||
			targetType == long.class || targetType == Long.class ||
			targetType == double.class || targetType == Double.class ||
			targetType == float.class || targetType == Float.class ||
			targetType == short.class || targetType == Short.class ||
			targetType == byte.class || targetType == Byte.class) {
			return arg instanceof Number;
		}
		if (targetType == boolean.class || targetType == Boolean.class) {
			return arg instanceof Boolean;
		}
		if (targetType == char.class || targetType == Character.class) {
			return arg instanceof Character;
		}
		if (targetType == String.class || targetType == CharSequence.class) {
			return arg instanceof CharSequence;
		}
		if (targetType.isInterface()) {
			if (arg instanceof JSFunction || arg instanceof JSObject) {
				return true;
			}
		}
		return false;
	}

	private static Object instantiateSubclass(ClassInfo info, JSObject jsObj, long mask, Object[] args) {
		if (args.length == 0 && info.noArgSubConstructor != null) {
			try {
				return info.noArgSubConstructor.newInstance(jsObj, mask);
			} catch (Throwable t) {
				throw new RuntimeException("Failed to instantiate dynamic subclass for " + info.targetClass.getName(), t);
			}
		}

		Constructor<?> bestCtor = null;
		Object[] castedArgs = null;

		// 1. 精确匹配参数个数
		for (Constructor<?> c : info.subConstructors) {
			Class<?>[] pTypes = c.getParameterTypes();
			if (pTypes.length < 2 || pTypes[0] != JSObject.class || pTypes[1] != long.class) continue;

			int superArgCount = pTypes.length - 2;
			if (superArgCount == args.length) {
				boolean match = true;
				Object[] tempArgs = new Object[pTypes.length];
				tempArgs[0] = jsObj;
				tempArgs[1] = mask;
				for (int i = 0; i < args.length; i++) {
					if (!isTypeCompatible(args[i], pTypes[i + 2])) {
						match = false;
						break;
					}
					try {
						tempArgs[i + 2] = JSLinker.castValue(args[i], pTypes[i + 2]);
					} catch (Throwable t) {
						match = false;
						break;
					}
				}
				if (match) {
					bestCtor = c;
					castedArgs = tempArgs;
					break;
				}
			}
		}

		// 2. 前缀匹配参数个数 (例如 JS 子类参数多于父类参数)
		if (bestCtor == null && args.length > 0) {
			for (Constructor<?> c : info.subConstructors) {
				Class<?>[] pTypes = c.getParameterTypes();
				if (pTypes.length < 2 || pTypes[0] != JSObject.class || pTypes[1] != long.class) continue;

				int superArgCount = pTypes.length - 2;
				if (superArgCount > 0 && superArgCount < args.length) {
					boolean match = true;
					Object[] tempArgs = new Object[pTypes.length];
					tempArgs[0] = jsObj;
					tempArgs[1] = mask;
					for (int i = 0; i < superArgCount; i++) {
						if (!isTypeCompatible(args[i], pTypes[i + 2])) {
							match = false;
							break;
						}
						try {
							tempArgs[i + 2] = JSLinker.castValue(args[i], pTypes[i + 2]);
						} catch (Throwable t) {
							match = false;
							break;
						}
					}
					if (match) {
						bestCtor = c;
						castedArgs = tempArgs;
						break;
					}
				}
			}
		}

		// 3. 回退匹配无参构造函数
		if (bestCtor == null) {
			for (Constructor<?> c : info.subConstructors) {
				Class<?>[] pTypes = c.getParameterTypes();
				if (pTypes.length == 2 && pTypes[0] == JSObject.class && pTypes[1] == long.class) {
					bestCtor = c;
					castedArgs = new Object[]{jsObj, mask};
					break;
				}
			}
			if (bestCtor == null) {
				throw new RuntimeException("No matching constructor in " + info.targetClass.getName() + " for " + args.length + " args");
			}
		}

		try {
			return bestCtor.newInstance(castedArgs);
		} catch (Throwable t) {
			throw new RuntimeException("Failed to instantiate dynamic subclass for " + info.targetClass.getName(), t);
		}
	}

	private static ClassInfo generateSubclass(Class<?> superClass) {
		if (Modifier.isFinal(superClass.getModifiers())) {
			throw new IllegalArgumentException("Cannot extend final class: " + superClass.getName());
		}

		boolean isInterface = superClass.isInterface();
		Class<?> actualSuperClass = isInterface ? Object.class : superClass;
		String superInternal = Type.getInternalName(actualSuperClass);

		String superPkg = superClass.getPackageName();
		boolean isSystemPkg = superPkg.startsWith("java.") || superPkg.startsWith("javax.") || superPkg.startsWith("jdk.") || superPkg.startsWith("sun.");

		ClassLoader appLoader = JavaClassExtender.class.getClassLoader();
		ClassLoader loader = superClass.getClassLoader();
		boolean canSuperLoaderSeeJS = false;
		if (loader != null) {
			try {
				loader.loadClass(JSBridgedObject.class.getName());
				canSuperLoaderSeeJS = true;
			} catch (Throwable ignored) {
			}
		}

		ClassLoader targetLoader;
		String pkg;
		if (!isSystemPkg && canSuperLoaderSeeJS && !superPkg.isEmpty()) {
			targetLoader = loader;
			pkg = superPkg.replace('.', '/') + "/";
		} else {
			targetLoader = appLoader;
			pkg = "hope/magic/js/gen/";
		}

		String simpleName = superClass.getSimpleName().replaceAll("[^a-zA-Z0-9_]", "_");
		if (simpleName.isEmpty()) simpleName = "Class";
		String subInternal = pkg + "JavaSubclass_" + simpleName + "_" + ID_GEN.incrementAndGet();

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

		String[] interfaces;
		if (isInterface) {
			interfaces = new String[]{Type.getInternalName(superClass), Type.getInternalName(JSBridgedObject.class)};
		} else {
			interfaces = new String[]{Type.getInternalName(JSBridgedObject.class)};
		}

		cw.visit(V1_8, ACC_PUBLIC | ACC_FINAL, subInternal, null, superInternal, interfaces);

		// 字段
		cw.visitField(ACC_PUBLIC | ACC_FINAL, "__magic_jsObj", "Lhope/magic/js/runtime/JSObject;", null, null).visitEnd();
		cw.visitField(ACC_PUBLIC | ACC_FINAL, "__magic_override_mask", "J", null, null).visitEnd();

		// 实现 JSBridgedObject.getJSObject()
		MethodVisitor gmv = cw.visitMethod(ACC_PUBLIC, "getJSObject", "()Lhope/magic/js/runtime/JSObject;", null, null);
		gmv.visitCode();
		gmv.visitVarInsn(ALOAD, 0);
		gmv.visitFieldInsn(GETFIELD, subInternal, "__magic_jsObj", "Lhope/magic/js/runtime/JSObject;");
		gmv.visitInsn(ARETURN);
		gmv.visitMaxs(0, 0);
		gmv.visitEnd();

		// 收集构造函数
		List<Constructor<?>> visibleCtors = new ArrayList<>();
		if (isInterface) {
			// 接口默认调用 Object()
			generateConstructor(cw, subInternal, superInternal, new Class<?>[0]);
		} else {
			for (Constructor<?> c : superClass.getDeclaredConstructors()) {
				int mod = c.getModifiers();
				if (Modifier.isPublic(mod) || Modifier.isProtected(mod)) {
					visibleCtors.add(c);
					generateConstructor(cw, subInternal, superInternal, c.getParameterTypes());
				}
			}
			if (visibleCtors.isEmpty()) {
				throw new IllegalArgumentException("Cannot extend class " + superClass.getName() + ": no accessible public or protected constructor");
			}
		}

		// 收集虚方法并分配掩码位
		Map<String, Long> methodMasks = new HashMap<>();
		long nextMaskShift = 0;

		Map<String, Method> virtualMethods = new LinkedHashMap<>();
		collectVirtualMethods(superClass, virtualMethods);

		for (Method m : virtualMethods.values()) {
			String name = m.getName();
			if (!methodMasks.containsKey(name)) {
				methodMasks.put(name, nextMaskShift++);
			}
			long maskShift = methodMasks.get(name);
			generateOverriddenMethod(cw, subInternal, superInternal, m, maskShift, isInterface);

			// 生成 __magic_super_xxx 桥接
			if (!Modifier.isAbstract(m.getModifiers())) {
				generateSuperBridgeMethod(cw, subInternal, superInternal, m, isInterface);
			}
		}

		cw.visitEnd();
		byte[] bytes = cw.toByteArray();

		Class<?> subClass = Magic.defineClass(targetLoader, bytes);
		List<Constructor<?>> subConstructors = Arrays.asList(subClass.getDeclaredConstructors());
		for (Constructor<?> c : subConstructors) {
			try {
				c.setAccessible(true);
			} catch (Throwable ignored) {
			}
		}

		return new ClassInfo(superClass, subClass, methodMasks, visibleCtors, subConstructors);
	}

	private static void collectVirtualMethods(Class<?> clazz, Map<String, Method> methods) {
		for (Method m : clazz.getMethods()) {
			int mod = m.getModifiers();
			if (Modifier.isStatic(mod) || Modifier.isFinal(mod) || Modifier.isPrivate(mod)) continue;
			if (m.getDeclaringClass() == Object.class) {
				String name = m.getName();
				if ("getClass".equals(name) || "wait".equals(name) || "notify".equals(name) || "notifyAll".equals(name)) continue;
			}
			String sig = m.getName() + Type.getMethodDescriptor(m);
			methods.putIfAbsent(sig, m);
		}
		// 获取受保护方法
		Class<?> curr = clazz;
		while (curr != null && curr != Object.class) {
			for (Method m : curr.getDeclaredMethods()) {
				int mod = m.getModifiers();
				if (Modifier.isProtected(mod) && !Modifier.isStatic(mod) && !Modifier.isFinal(mod)) {
					String sig = m.getName() + Type.getMethodDescriptor(m);
					methods.putIfAbsent(sig, m);
				}
			}
			curr = curr.getSuperclass();
		}
	}

	private static void generateConstructor(ClassWriter cw, String subInternal, String superInternal, Class<?>[] paramTypes) {
		// 签名: <init>(JSObject jsObj, long mask, P1, P2...)
		List<Class<?>> allParams = new ArrayList<>();
		allParams.add(JSObject.class);
		allParams.add(long.class);
		allParams.addAll(Arrays.asList(paramTypes));

		Type[] types = new Type[allParams.size()];
		for (int i = 0; i < allParams.size(); i++) types[i] = Type.getType(allParams.get(i));
		String desc = Type.getMethodDescriptor(Type.VOID_TYPE, types);

		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", desc, null, null);
		mv.visitCode();

		// super(p1, p2...)
		mv.visitVarInsn(ALOAD, 0);
		int slot = 4; // slot 0 = this, slot 1 = jsObj, slot 2,3 = mask
		for (Class<?> pt : paramTypes) {
			Type t = Type.getType(pt);
			mv.visitVarInsn(t.getOpcode(ILOAD), slot);
			slot += t.getSize();
		}

		Type[] superParamTypes = new Type[paramTypes.length];
		for (int i = 0; i < paramTypes.length; i++) superParamTypes[i] = Type.getType(paramTypes[i]);
		String superDesc = Type.getMethodDescriptor(Type.VOID_TYPE, superParamTypes);

		mv.visitMethodInsn(INVOKESPECIAL, superInternal, "<init>", superDesc, false);

		// this.__magic_jsObj = jsObj
		mv.visitVarInsn(ALOAD, 0);
		mv.visitVarInsn(ALOAD, 1);
		mv.visitFieldInsn(PUTFIELD, subInternal, "__magic_jsObj", "Lhope/magic/js/runtime/JSObject;");

		// this.__magic_override_mask = mask
		mv.visitVarInsn(ALOAD, 0);
		mv.visitVarInsn(LLOAD, 2);
		mv.visitFieldInsn(PUTFIELD, subInternal, "__magic_override_mask", "J");

		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private static void generateOverriddenMethod(ClassWriter cw, String subInternal, String superInternal, Method m, long maskShift, boolean isInterface) {
		String name = m.getName();
		String desc = Type.getMethodDescriptor(m);
		Class<?>[] paramTypes = m.getParameterTypes();
		Class<?> retType = m.getReturnType();
		boolean isAbstract = Modifier.isAbstract(m.getModifiers());

		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
		mv.visitCode();

		Label callJsLabel = new Label();
		Label fallbackLabel = new Label();

		// 1. 掩码快速短路 (若 maskShift < 64 且非抽象)
		if (maskShift < 64 && !isAbstract) {
			mv.visitVarInsn(ALOAD, 0);
			mv.visitFieldInsn(GETFIELD, subInternal, "__magic_override_mask", "J");
			mv.visitLdcInsn(1L << maskShift);
			mv.visitInsn(LAND);
			mv.visitInsn(LCONST_0);
			mv.visitInsn(LCMP);
			mv.visitJumpInsn(IFNE, callJsLabel);

			// 未覆写：快速直通父类原生实现
			emitSuperCall(mv, superInternal, m, paramTypes, retType, isInterface);
		}

		// 2. 慢路径：调用 JSFunction
		mv.visitLabel(callJsLabel);
		mv.visitVarInsn(ALOAD, 0);
		mv.visitFieldInsn(GETFIELD, subInternal, "__magic_jsObj", "Lhope/magic/js/runtime/JSObject;");
		pushInt(mv, SymbolTable.id(name));
		mv.visitMethodInsn(INVOKEVIRTUAL, "hope/magic/js/runtime/JSObject", "get", "(I)Ljava/lang/Object;", false);

		mv.visitInsn(DUP);
		mv.visitTypeInsn(INSTANCEOF, "hope/magic/js/runtime/JSFunction");
		mv.visitJumpInsn(IFEQ, fallbackLabel);

		// 调用 JSFunction.call(cx, thisObj, args)
		mv.visitTypeInsn(CHECKCAST, "hope/magic/js/runtime/JSFunction");
		mv.visitInsn(ACONST_NULL); // cx
		mv.visitVarInsn(ALOAD, 0); // thisObj
		emitPackArgs(mv, paramTypes);
		mv.visitMethodInsn(INVOKEINTERFACE, "hope/magic/js/runtime/JSFunction", "call", "(Lhope/magic/js/runtime/JSContext;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", true);

		// 返回值规范化强转
		emitCastReturn(mv, retType);

		// 3. Fallback: 属性不是 JSFunction
		mv.visitLabel(fallbackLabel);
		mv.visitInsn(POP); // 弹出 member
		if (!isAbstract) {
			emitSuperCall(mv, superInternal, m, paramTypes, retType, isInterface);
		} else {
			mv.visitTypeInsn(NEW, "java/lang/UnsupportedOperationException");
			mv.visitInsn(DUP);
			mv.visitLdcInsn("Abstract method " + name + " is not implemented in JavaScript");
			mv.visitMethodInsn(INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>", "(Ljava/lang/String;)V", false);
			mv.visitInsn(ATHROW);
		}

		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private static void generateSuperBridgeMethod(ClassWriter cw, String subInternal, String superInternal, Method m, boolean isInterface) {
		String name = m.getName();
		String bridgeName = "__magic_super_" + name;
		String desc = Type.getMethodDescriptor(m);
		Class<?>[] paramTypes = m.getParameterTypes();
		Class<?> retType = m.getReturnType();

		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, bridgeName, desc, null, null);
		mv.visitCode();
		emitSuperCall(mv, superInternal, m, paramTypes, retType, isInterface);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private static void emitSuperCall(MethodVisitor mv, String superInternal, Method m, Class<?>[] paramTypes, Class<?> retType, boolean isInterface) {
		mv.visitVarInsn(ALOAD, 0);
		int slot = 1;
		for (Class<?> pt : paramTypes) {
			Type t = Type.getType(pt);
			mv.visitVarInsn(t.getOpcode(ILOAD), slot);
			slot += t.getSize();
		}
		boolean isItf;
		String targetInternal;
		if (isInterface) {
			if (m.getDeclaringClass() == Object.class) {
				targetInternal = "java/lang/Object";
				isItf = false;
			} else {
				targetInternal = superInternal;
				isItf = true;
			}
		} else {
			targetInternal = superInternal;
			isItf = false;
		}
		mv.visitMethodInsn(INVOKESPECIAL, targetInternal, m.getName(), Type.getMethodDescriptor(m), isItf);
		Type retT = Type.getType(retType);
		mv.visitInsn(retT.getOpcode(IRETURN));
	}

	private static void emitPackArgs(MethodVisitor mv, Class<?>[] paramTypes) {
		pushInt(mv, paramTypes.length);
		mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");

		int slot = 1;
		for (int i = 0; i < paramTypes.length; i++) {
			Class<?> pt = paramTypes[i];
			mv.visitInsn(DUP);
			pushInt(mv, i);
			Type t = Type.getType(pt);
			mv.visitVarInsn(t.getOpcode(ILOAD), slot);
			slot += t.getSize();
			boxPrimitive(mv, pt);
			mv.visitInsn(AASTORE);
		}
	}

	private static void boxPrimitive(MethodVisitor mv, Class<?> pt) {
		if (pt == int.class) mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
		else if (pt == double.class) mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
		else if (pt == long.class) mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
		else if (pt == boolean.class) mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
		else if (pt == float.class) mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
		else if (pt == byte.class) mv.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
		else if (pt == short.class) mv.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
		else if (pt == char.class) mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
	}

	private static void emitCastReturn(MethodVisitor mv, Class<?> retType) {
		if (retType == void.class) {
			mv.visitInsn(POP);
			mv.visitInsn(RETURN);
		} else if (retType == boolean.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toBoolean", "(Ljava/lang/Object;)Z", false);
			mv.visitInsn(IRETURN);
		} else if (retType == int.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toInt", "(Ljava/lang/Object;)I", false);
			mv.visitInsn(IRETURN);
		} else if (retType == double.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toDouble", "(Ljava/lang/Object;)D", false);
			mv.visitInsn(DRETURN);
		} else if (retType == long.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toLong", "(Ljava/lang/Object;)J", false);
			mv.visitInsn(LRETURN);
		} else if (retType == float.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toDouble", "(Ljava/lang/Object;)D", false);
			mv.visitInsn(D2F);
			mv.visitInsn(FRETURN);
		} else if (retType == short.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toInt", "(Ljava/lang/Object;)I", false);
			mv.visitInsn(I2S);
			mv.visitInsn(IRETURN);
		} else if (retType == byte.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toInt", "(Ljava/lang/Object;)I", false);
			mv.visitInsn(I2B);
			mv.visitInsn(IRETURN);
		} else if (retType == char.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toInt", "(Ljava/lang/Object;)I", false);
			mv.visitInsn(I2C);
			mv.visitInsn(IRETURN);
		} else if (retType == String.class) {
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSOps", "toStr", "(Ljava/lang/Object;)Ljava/lang/String;", false);
			mv.visitInsn(ARETURN);
		} else {
			// 对象类型
			mv.visitLdcInsn(Type.getType(retType));
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSLinker", "castValue", "(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;", false);
			mv.visitTypeInsn(CHECKCAST, Type.getInternalName(retType));
			mv.visitInsn(ARETURN);
		}
	}

	private static void pushInt(MethodVisitor mv, int value) {
		if (value >= -1 && value <= 5) {
			mv.visitInsn(ICONST_0 + value);
		} else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
			mv.visitIntInsn(BIPUSH, value);
		} else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
			mv.visitIntInsn(SIPUSH, value);
		} else {
			mv.visitLdcInsn(value);
		}
	}
}
