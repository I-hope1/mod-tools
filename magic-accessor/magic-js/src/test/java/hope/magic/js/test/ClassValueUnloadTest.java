package hope.magic.js.test;

import hope.magic.annotation.AccessMode;
import hope.magic.js.runtime.*;
import hope.magic.runtime.Magic;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;

import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.List;

public class ClassValueUnloadTest {
	static {
		Magic.install();
	}

	private static class SimpleClassLoader extends ClassLoader {
		public SimpleClassLoader(ClassLoader parent) {
			super(parent);
		}

		public Class<?> define(String name, byte[] bytes) {
			return defineClass(name, bytes, 0, bytes.length);
		}
	}

	private WeakReference<ClassLoader> exerciseReflection(WeakReference<Class<?>>[] classRefHolder) throws Throwable {
		SimpleClassLoader loader = new SimpleClassLoader(getClass().getClassLoader());

		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "hope/magic/test/DummyPlugin", null, "java/lang/Object", null);

		// public int count = 42
		cw.visitField(Opcodes.ACC_PUBLIC, "count", "I", null, null).visitEnd();

		// <init>()
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitIntInsn(Opcodes.BIPUSH, 42);
		mv.visitFieldInsn(Opcodes.PUTFIELD, "hope/magic/test/DummyPlugin", "count", "I");
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(2, 1);
		mv.visitEnd();

		// private <init>(int initialCount)
		MethodVisitor mvPrivCtor = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "(I)V", null, null);
		mvPrivCtor.visitCode();
		mvPrivCtor.visitVarInsn(Opcodes.ALOAD, 0);
		mvPrivCtor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		mvPrivCtor.visitVarInsn(Opcodes.ALOAD, 0);
		mvPrivCtor.visitVarInsn(Opcodes.ILOAD, 1);
		mvPrivCtor.visitFieldInsn(Opcodes.PUTFIELD, "hope/magic/test/DummyPlugin", "count", "I");
		mvPrivCtor.visitInsn(Opcodes.RETURN);
		mvPrivCtor.visitMaxs(2, 2);
		mvPrivCtor.visitEnd();

		// public String doWork()
		MethodVisitor mv2 = cw.visitMethod(Opcodes.ACC_PUBLIC, "doWork", "()Ljava/lang/String;", null, null);
		mv2.visitCode();
		mv2.visitLdcInsn("plugin-result");
		mv2.visitInsn(Opcodes.ARETURN);
		mv2.visitMaxs(1, 1);
		mv2.visitEnd();

		// public int getCount()
		MethodVisitor mv3 = cw.visitMethod(Opcodes.ACC_PUBLIC, "getCount", "()I", null, null);
		mv3.visitCode();
		mv3.visitVarInsn(Opcodes.ALOAD, 0);
		mv3.visitFieldInsn(Opcodes.GETFIELD, "hope/magic/test/DummyPlugin", "count", "I");
		mv3.visitInsn(Opcodes.IRETURN);
		mv3.visitMaxs(1, 1);
		mv3.visitEnd();

		// private String secret()
		MethodVisitor mv4 = cw.visitMethod(Opcodes.ACC_PRIVATE, "secret", "()Ljava/lang/String;", null, null);
		mv4.visitCode();
		mv4.visitLdcInsn("secret-value");
		mv4.visitInsn(Opcodes.ARETURN);
		mv4.visitMaxs(1, 1);
		mv4.visitEnd();

		cw.visitEnd();
		byte[]   bytes = cw.toByteArray();
		Class<?> clazz = loader.define("hope.magic.test.DummyPlugin", bytes);

		classRefHolder[0] = new WeakReference<>(clazz);

		// 1. MethodResolver 缓存存取测试
		Method m = MethodResolver.findMethod(clazz, "doWork", 0);
		Assertions.assertNotNull(m);

		Constructor<?> ctor = MethodResolver.findConstructor(clazz, 0);
		Assertions.assertNotNull(ctor);

		List<Method> candidates = MethodResolver.findCandidateMethods(clazz, "doWork");
		Assertions.assertEquals(1, candidates.size());

		Method getter = MethodResolver.findGetterMethod(clazz, "count");
		Assertions.assertNotNull(getter);

		// 2. MagicJIT 存根缓存测试
		MagicJIT.MagicInvoker invoker = MagicJIT.getMethodInvoker(clazz, "doWork", 0, false);
		Assertions.assertNotNull(invoker);
		Object instance = ctor.newInstance();
		Object result   = invoker.invoke(instance, new Object[0]);
		Assertions.assertEquals("plugin-result", result);

		MagicJIT.MagicConstructorInvoker ctorInvoker = MagicJIT.getConstructorInvoker(clazz, 0);
		Assertions.assertNotNull(ctorInvoker);
		Object instance2 = ctorInvoker.newInstance(new Object[0]);
		Assertions.assertNotNull(instance2);

		var fieldGetter = MagicJIT.getFieldGetterStub(clazz, "count");
		Assertions.assertNotNull(fieldGetter);
		int countVal = ((Number) fieldGetter.invoke(instance)).intValue();
		Assertions.assertEquals(42, countVal);

		var exactStub = MagicJIT.createExactMethodStub(clazz, m);
		Assertions.assertNotNull(exactStub);
		Object exactRes = exactStub.invoke(instance);
		Assertions.assertEquals("plugin-result", exactRes);

		// 3. 测试 MAGIC_ACCESSOR 模式下直接调用 private 方法
		MagicJIT.MagicInvoker privateInvoker = MagicJIT.createMethodInvoker(clazz, "secret", 0, false, AccessMode.MAGIC_ACCESSOR);
		Assertions.assertNotNull(privateInvoker);
		Object privateRes = privateInvoker.invoke0(instance);
		Assertions.assertEquals("secret-value", privateRes);

		// 4. 测试 MAGIC_ACCESSOR 模式下直接调用 private 构造器
		MagicJIT.MagicConstructorInvoker privateCtorInvoker = MagicJIT.createConstructorInvoker(clazz, 1, AccessMode.MAGIC_ACCESSOR);
		Assertions.assertNotNull(privateCtorInvoker);
		Object privateInstance = privateCtorInvoker.newInstance1(99);
		Assertions.assertNotNull(privateInstance);
		int countFromPrivateCtor = ((Number) fieldGetter.invoke(privateInstance)).intValue();
		Assertions.assertEquals(99, countFromPrivateCtor);

		return new WeakReference<>(loader);
	}

	@Test
	public void testClassLoaderCanBeUnloadedAfterReflectionAndJITCaching() throws Throwable {
		MagicJIT.setMode(AccessMode.AUTO); // 验证 UNSAFE_AND_LINKTO 方案 A 下无泄漏卸载

		@SuppressWarnings("unchecked")
		WeakReference<Class<?>>[] classRefHolder = new WeakReference[1];
		WeakReference<ClassLoader> loaderRef = exerciseReflection(classRefHolder);

		// 验证强引用在局部作用域退出后断开，反复触发 GC
		boolean collected = false;
		for (int i = 0; i < 50; i++) {
			System.gc();
			if (loaderRef.get() == null && classRefHolder[0].get() == null) {
				collected = true;
				break;
			}
			Thread.sleep(20);
		}

		Assertions.assertTrue(collected, "ClassLoader and Class must be completely garbage-collected, proving no global ConcurrentHashMap leaks!");
	}

	private WeakReference<ClassLoader> exerciseScriptCompileAndRun(WeakReference<Class<?>>[] classRefHolder) throws Throwable {
		hope.magic.js.runtime.JSScript script = hope.magic.js.compiler.JSCompiler.compile(
			"""
			function outer(x) {
			    function inner(y) {
			        return x + y;
			    }
			    return inner;
			}
			outer(10)(20);
			"""
		);

		Class<?> scriptClass = script.getClass();
		ClassLoader scriptLoader = scriptClass.getClassLoader();
		Assertions.assertInstanceOf(hope.magic.js.compiler.JSCompiler.ScriptClassLoader.class, scriptLoader);

		hope.magic.js.runtime.JSContext cx = new hope.magic.js.runtime.JSContext();
		Object res = script.run(cx);
		Assertions.assertEquals(30.0, res);

		classRefHolder[0] = new WeakReference<>(scriptClass);
		return new WeakReference<>(scriptLoader);
	}

	@Test
	public void testScriptClassLoaderCanBeUnloaded() throws Throwable {
		@SuppressWarnings("unchecked")
		WeakReference<Class<?>>[] classRefHolder = new WeakReference[1];
		WeakReference<ClassLoader> loaderRef = exerciseScriptCompileAndRun(classRefHolder);

		boolean collected = false;
		for (int i = 0; i < 50; i++) {
			System.gc();
			if (loaderRef.get() == null && classRefHolder[0].get() == null) {
				collected = true;
				break;
			}
			Thread.sleep(20);
		}

		Assertions.assertTrue(collected, "ScriptClassLoader and dynamic JSScript class must be completely garbage-collected upon dereference!");
	}

	@Test
	public void testResolveOrFail() throws Throwable {
		// 1. 测试虚拟方法 resolveOrFail (byte 5 = REF_invokeVirtual)
		Object mnVirtual = MagicJIT.resolveOrFail((byte) 5, String.class, "length", java.lang.invoke.MethodType.methodType(int.class));
		Assertions.assertNotNull(mnVirtual);

		// 2. 测试静态方法 resolveOrFail (byte 6 = REF_invokeStatic)
		Object mnStatic = MagicJIT.resolveOrFail((byte) 6, Math.class, "max", java.lang.invoke.MethodType.methodType(int.class, int.class, int.class));
		Assertions.assertNotNull(mnStatic);

		// 3. 测试构造器 resolveOrFail (byte 7 = REF_invokeSpecial)
		Object mnCtor = MagicJIT.resolveOrFail((byte) 7, String.class, "<init>", java.lang.invoke.MethodType.methodType(void.class));
		Assertions.assertNotNull(mnCtor);

		// 4. 测试字段读取 resolveOrFail (byte 1 = REF_getField)
		Object mnField = MagicJIT.resolveOrFail((byte) 1, Point.class, "x", int.class);
		Assertions.assertNotNull(mnField);
	}

	@Test
	public void testBridgeCacheReuseAndMetaspaceProtection() throws Throwable {
		MagicJIT.setMode(AccessMode.AUTO);

		int methodBridgesAfterFirst = -1;
		int ctorBridgesAfterFirst = -1;

		for (int i = 0; i < 10; i++) {
			SimpleClassLoader loader = new SimpleClassLoader(getClass().getClassLoader());
			ClassWriter cw = new ClassWriter(0);
			String internalName = "hope/magic/test/Plugin_" + i;
			cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

			// <init>()
			MethodVisitor ctorMv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
			ctorMv.visitCode();
			ctorMv.visitVarInsn(Opcodes.ALOAD, 0);
			ctorMv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
			ctorMv.visitInsn(Opcodes.RETURN);
			ctorMv.visitMaxs(1, 1);
			ctorMv.visitEnd();

			// public int calc(int a, int b)
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "calc", "(II)I", null, null);
			mv.visitCode();
			mv.visitVarInsn(Opcodes.ILOAD, 1);
			mv.visitVarInsn(Opcodes.ILOAD, 2);
			mv.visitInsn(Opcodes.IADD);
			mv.visitInsn(Opcodes.IRETURN);
			mv.visitMaxs(2, 3);
			mv.visitEnd();

			cw.visitEnd();
			Class<?> cls = loader.define(internalName.replace('/', '.'), cw.toByteArray());

			MagicJIT.MagicConstructorInvoker ctorInvoker = MagicJIT.getConstructorInvoker(cls, 0);
			Assertions.assertNotNull(ctorInvoker);
			Object instance = ctorInvoker.newInstance(new Object[0]);

			MagicJIT.MagicInvoker invoker = MagicJIT.getMethodInvoker(cls, "calc", 2, false);
			Assertions.assertNotNull(invoker);
			Object res = invoker.invoke(instance, new Object[]{ 10, 20 });
			Assertions.assertEquals(30, res);
			Assertions.assertEquals(30, invoker.invoke2(instance, 10, 20));
			Assertions.assertEquals(30, invoker.invokeInt2(instance, 10, 20));

			// 验证重复获取相同类的方法/构造器时，复用缓存且不重复生成 Bridge
			int mbBefore = MagicJIT.getMethodBridgeCacheSize();
			int cbBefore = MagicJIT.getCtorBridgeCacheSize();
			MagicJIT.MagicConstructorInvoker ctorInvoker2 = MagicJIT.getConstructorInvoker(cls, 0);
			MagicJIT.MagicInvoker invoker2 = MagicJIT.getMethodInvoker(cls, "calc", 2, false);
			Assertions.assertSame(ctorInvoker, ctorInvoker2);
			Assertions.assertSame(invoker, invoker2);
			Assertions.assertEquals(mbBefore, MagicJIT.getMethodBridgeCacheSize(), "Method bridge must be cached and reused for same method");
			Assertions.assertEquals(cbBefore, MagicJIT.getCtorBridgeCacheSize(), "Ctor bridge must be cached and reused for same ctor");
		}
	}

	private static Class<?> getOrCreateBootstrapInvokerInterface() throws Throwable {
		String name = "java.lang.invoke.MagicInvokerBootstrap";
		try {
			return Class.forName(name, false, null);
		} catch (ClassNotFoundException e) {
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
				name.replace('.', '/'), null, "java/lang/Object", null);

			cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "invokeInt2",
				"(Ljava/lang/Object;II)I", null, new String[]{"java/lang/Throwable"}).visitEnd();

			cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "invoke2",
				"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"}).visitEnd();

			cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "invoke",
				"(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"}).visitEnd();

			cw.visitEnd();
			return Magic.defineClass(null, cw.toByteArray());
		}
	}

	private WeakReference<?>[] exercisePlanB() throws Throwable {
		Class<?> bootIface = getOrCreateBootstrapInvokerInterface();
		String bootIfaceInternal = org.objectweb.asm.Type.getInternalName(bootIface);

		// Target class in custom ClassLoader
		SimpleClassLoader pluginLoader = new SimpleClassLoader(getClass().getClassLoader());
		ClassWriter targetCw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		targetCw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "hope/magic/test/PluginTarget", null, "java/lang/Object", null);

		MethodVisitor initMv = targetCw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		initMv.visitCode();
		initMv.visitVarInsn(Opcodes.ALOAD, 0);
		initMv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		initMv.visitInsn(Opcodes.RETURN);
		initMv.visitMaxs(1, 1);
		initMv.visitEnd();

		MethodVisitor mulMv = targetCw.visitMethod(Opcodes.ACC_PUBLIC, "multiply", "(II)I", null, null);
		mulMv.visitCode();
		mulMv.visitVarInsn(Opcodes.ILOAD, 1);
		mulMv.visitVarInsn(Opcodes.ILOAD, 2);
		mulMv.visitInsn(Opcodes.IMUL);
		mulMv.visitInsn(Opcodes.IRETURN);
		mulMv.visitMaxs(2, 3);
		mulMv.visitEnd();
		targetCw.visitEnd();

		Class<?> pluginClass = pluginLoader.define("hope.magic.test.PluginTarget", targetCw.toByteArray());
		Object pluginInstance = pluginClass.getDeclaredConstructor().newInstance();
		Method multiplyMethod = pluginClass.getMethod("multiply", int.class, int.class);

		java.lang.invoke.MethodHandle mh = Magic.lookup.unreflect(multiplyMethod);
		Object mn = hope.magic.runtime.LinkerHelper.extractMemberName(mh);

		// 1. Generate Hidden Class in java.lang.invoke implementing MagicInvokerBootstrap
		String hiddenClassName = "java/lang/invoke/PlanBHiddenInvoker";
		ClassWriter hw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		hw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, hiddenClassName, null, "java/lang/Object",
			new String[]{ bootIfaceInternal });

		FieldVisitor fv = hw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "MN", "Ljava/lang/Object;", null, null);
		fv.visitAnnotation("Ljdk/internal/vm/annotation/Stable;", true).visitEnd();
		fv.visitEnd();

		MethodVisitor hInit = hw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		hInit.visitCode();
		hInit.visitVarInsn(Opcodes.ALOAD, 0);
		hInit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		hInit.visitInsn(Opcodes.RETURN);
		hInit.visitMaxs(1, 1);
		hInit.visitEnd();

		// invokeInt2: GETSTATIC MN -> linkToVirtual
		MethodVisitor hInvoke = hw.visitMethod(Opcodes.ACC_PUBLIC, "invokeInt2", "(Ljava/lang/Object;II)I", null, new String[]{"java/lang/Throwable"});
		hInvoke.visitAnnotation("Ljdk/internal/vm/annotation/ForceInline;", true).visitEnd();
		hInvoke.visitCode();
		hInvoke.visitVarInsn(Opcodes.ALOAD, 1);
		hInvoke.visitVarInsn(Opcodes.ILOAD, 2);
		hInvoke.visitVarInsn(Opcodes.ILOAD, 3);
		hInvoke.visitFieldInsn(Opcodes.GETSTATIC, hiddenClassName, "MN", "Ljava/lang/Object;");
		hInvoke.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodHandle", "linkToVirtual",
			"(Ljava/lang/Object;IILjava/lang/invoke/MemberName;)I", false);
		hInvoke.visitInsn(Opcodes.IRETURN);
		hInvoke.visitMaxs(4, 4);
		hInvoke.visitEnd();

		// invoke2
		MethodVisitor hInvoke2 = hw.visitMethod(Opcodes.ACC_PUBLIC, "invoke2", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
		hInvoke2.visitCode();
		hInvoke2.visitVarInsn(Opcodes.ALOAD, 0);
		hInvoke2.visitVarInsn(Opcodes.ALOAD, 1);
		hInvoke2.visitVarInsn(Opcodes.ALOAD, 2);
		hInvoke2.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
		hInvoke2.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
		hInvoke2.visitVarInsn(Opcodes.ALOAD, 3);
		hInvoke2.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
		hInvoke2.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
		hInvoke2.visitMethodInsn(Opcodes.INVOKEVIRTUAL, hiddenClassName, "invokeInt2", "(Ljava/lang/Object;II)I", false);
		hInvoke2.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
		hInvoke2.visitInsn(Opcodes.ARETURN);
		hInvoke2.visitMaxs(4, 4);
		hInvoke2.visitEnd();

		// invoke
		MethodVisitor hInvokeArr = hw.visitMethod(Opcodes.ACC_PUBLIC, "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
		hInvokeArr.visitCode();
		hInvokeArr.visitVarInsn(Opcodes.ALOAD, 0);
		hInvokeArr.visitVarInsn(Opcodes.ALOAD, 1);
		hInvokeArr.visitVarInsn(Opcodes.ALOAD, 2);
		hInvokeArr.visitInsn(Opcodes.ICONST_0);
		hInvokeArr.visitInsn(Opcodes.AALOAD);
		hInvokeArr.visitVarInsn(Opcodes.ALOAD, 2);
		hInvokeArr.visitInsn(Opcodes.ICONST_1);
		hInvokeArr.visitInsn(Opcodes.AALOAD);
		hInvokeArr.visitMethodInsn(Opcodes.INVOKEVIRTUAL, hiddenClassName, "invoke2", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
		hInvokeArr.visitInsn(Opcodes.ARETURN);
		hInvokeArr.visitMaxs(5, 3);
		hInvokeArr.visitEnd();
		hw.visitEnd();

		java.lang.invoke.MethodHandles.Lookup invokeLookup = java.lang.invoke.MethodHandles.privateLookupIn(
			java.lang.invoke.MethodHandle.class, Magic.lookup
		);
		java.lang.invoke.MethodHandles.Lookup hiddenLookup = invokeLookup.defineHiddenClass(hw.toByteArray(), true);
		Class<?> hiddenClass = hiddenLookup.lookupClass();

		Field mnField = hiddenClass.getDeclaredField("MN");
		jdk.internal.misc.Unsafe jdkUnsafe = jdk.internal.misc.Unsafe.getUnsafe();
		long offset = jdkUnsafe.staticFieldOffset(mnField);
		Object base = jdkUnsafe.staticFieldBase(mnField);
		jdkUnsafe.putReference(base, offset, mn);

		Object rawHiddenInvoker = hiddenClass.getDeclaredConstructor().newInstance();

		// 2. Generate MagicInvoker in pluginLoader (or AppClassLoader) delegating to rawHiddenInvoker
		String appInvokerName = "hope/magic/test/PlanBAppInvoker";
		ClassWriter aw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		aw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, appInvokerName, null, "java/lang/Object",
			new String[]{ org.objectweb.asm.Type.getInternalName(MagicJIT.MagicInvoker.class) });

		FieldVisitor afv = aw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "delegate", "L" + bootIfaceInternal + ";", null, null);
		afv.visitAnnotation("Ljdk/internal/vm/annotation/Stable;", true).visitEnd();
		afv.visitEnd();

		MethodVisitor aInit = aw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + bootIfaceInternal + ";)V", null, null);
		aInit.visitCode();
		aInit.visitVarInsn(Opcodes.ALOAD, 0);
		aInit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		aInit.visitVarInsn(Opcodes.ALOAD, 0);
		aInit.visitVarInsn(Opcodes.ALOAD, 1);
		aInit.visitFieldInsn(Opcodes.PUTFIELD, appInvokerName, "delegate", "L" + bootIfaceInternal + ";");
		aInit.visitInsn(Opcodes.RETURN);
		aInit.visitMaxs(2, 2);
		aInit.visitEnd();

		// invokeInt2: delegate.invokeInt2
		MethodVisitor aInvoke = aw.visitMethod(Opcodes.ACC_PUBLIC, "invokeInt2", "(Ljava/lang/Object;II)I", null, new String[]{"java/lang/Throwable"});
		aInvoke.visitAnnotation("Ljdk/internal/vm/annotation/ForceInline;", true).visitEnd();
		aInvoke.visitCode();
		aInvoke.visitVarInsn(Opcodes.ALOAD, 0);
		aInvoke.visitFieldInsn(Opcodes.GETFIELD, appInvokerName, "delegate", "L" + bootIfaceInternal + ";");
		aInvoke.visitVarInsn(Opcodes.ALOAD, 1);
		aInvoke.visitVarInsn(Opcodes.ILOAD, 2);
		aInvoke.visitVarInsn(Opcodes.ILOAD, 3);
		aInvoke.visitMethodInsn(Opcodes.INVOKEINTERFACE, bootIfaceInternal, "invokeInt2", "(Ljava/lang/Object;II)I", true);
		aInvoke.visitInsn(Opcodes.IRETURN);
		aInvoke.visitMaxs(4, 4);
		aInvoke.visitEnd();

		// invoke2: delegate.invoke2
		MethodVisitor aInvoke2 = aw.visitMethod(Opcodes.ACC_PUBLIC, "invoke2", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
		aInvoke2.visitCode();
		aInvoke2.visitVarInsn(Opcodes.ALOAD, 0);
		aInvoke2.visitFieldInsn(Opcodes.GETFIELD, appInvokerName, "delegate", "L" + bootIfaceInternal + ";");
		aInvoke2.visitVarInsn(Opcodes.ALOAD, 1);
		aInvoke2.visitVarInsn(Opcodes.ALOAD, 2);
		aInvoke2.visitVarInsn(Opcodes.ALOAD, 3);
		aInvoke2.visitMethodInsn(Opcodes.INVOKEINTERFACE, bootIfaceInternal, "invoke2", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
		aInvoke2.visitInsn(Opcodes.ARETURN);
		aInvoke2.visitMaxs(4, 4);
		aInvoke2.visitEnd();

		// invoke: delegate.invoke
		MethodVisitor aInvokeArr = aw.visitMethod(Opcodes.ACC_PUBLIC, "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
		aInvokeArr.visitCode();
		aInvokeArr.visitVarInsn(Opcodes.ALOAD, 0);
		aInvokeArr.visitFieldInsn(Opcodes.GETFIELD, appInvokerName, "delegate", "L" + bootIfaceInternal + ";");
		aInvokeArr.visitVarInsn(Opcodes.ALOAD, 1);
		aInvokeArr.visitVarInsn(Opcodes.ALOAD, 2);
		aInvokeArr.visitMethodInsn(Opcodes.INVOKEINTERFACE, bootIfaceInternal, "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", true);
		aInvokeArr.visitInsn(Opcodes.ARETURN);
		aInvokeArr.visitMaxs(3, 3);
		aInvokeArr.visitEnd();
		aw.visitEnd();

		Class<?> appInvokerClass = pluginLoader.define("hope.magic.test.PlanBAppInvoker", aw.toByteArray());
		Constructor<?> appCtor = appInvokerClass.getConstructor(bootIface);
		MagicJIT.MagicInvoker invoker = (MagicJIT.MagicInvoker) appCtor.newInstance(rawHiddenInvoker);

		int res = invoker.invokeInt2(pluginInstance, 6, 7);
		Assertions.assertEquals(42, res);

		// Benchmark C2 inline performance with wrapper!
		for (int i = 0; i < 200_000; i++) {
			invoker.invokeInt2(pluginInstance, i, 2);
		}
		int iterations = 10_000_000;
		long start = System.nanoTime();
		long sum = 0;
		for (int i = 0; i < iterations; i++) {
			sum += invoker.invokeInt2(pluginInstance, i, 2);
		}
		double timeMs = (System.nanoTime() - start) / 1_000_000.0;
		System.out.printf("PLAN B (Wrapper Invoker) invokeInt2: %.2f ms (%.0f ops/ms)%n",
			timeMs, iterations / timeMs);

		// Benchmark direct invocation on rawHiddenInvoker via MagicInvokerBootstrap directly!
		String callerName = "hope/magic/test/DirectBootstrapCaller";
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, callerName, null, "java/lang/Object", null);
		MethodVisitor cmv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "runBenchmark",
			"(Ljava/lang/Object;Ljava/lang/Object;I)J", null, new String[]{"java/lang/Throwable"});
		cmv.visitCode();
		cmv.visitVarInsn(Opcodes.ALOAD, 0);
		cmv.visitTypeInsn(Opcodes.CHECKCAST, bootIfaceInternal);
		cmv.visitVarInsn(Opcodes.ASTORE, 3);
		cmv.visitInsn(Opcodes.LCONST_0);
		cmv.visitVarInsn(Opcodes.LSTORE, 4);
		cmv.visitInsn(Opcodes.ICONST_0);
		cmv.visitVarInsn(Opcodes.ISTORE, 6);
		org.objectweb.asm.Label loopStart = new org.objectweb.asm.Label();
		org.objectweb.asm.Label loopEnd = new org.objectweb.asm.Label();
		cmv.visitLabel(loopStart);
		cmv.visitVarInsn(Opcodes.ILOAD, 6);
		cmv.visitVarInsn(Opcodes.ILOAD, 2);
		cmv.visitJumpInsn(Opcodes.IF_ICMPGE, loopEnd);
		cmv.visitVarInsn(Opcodes.LLOAD, 4);
		cmv.visitVarInsn(Opcodes.ALOAD, 3);
		cmv.visitVarInsn(Opcodes.ALOAD, 1);
		cmv.visitVarInsn(Opcodes.ILOAD, 6);
		cmv.visitInsn(Opcodes.ICONST_2);
		cmv.visitMethodInsn(Opcodes.INVOKEINTERFACE, bootIfaceInternal, "invokeInt2", "(Ljava/lang/Object;II)I", true);
		cmv.visitInsn(Opcodes.I2L);
		cmv.visitInsn(Opcodes.LADD);
		cmv.visitVarInsn(Opcodes.LSTORE, 4);
		cmv.visitIincInsn(6, 1);
		cmv.visitJumpInsn(Opcodes.GOTO, loopStart);
		cmv.visitLabel(loopEnd);
		cmv.visitVarInsn(Opcodes.LLOAD, 4);
		cmv.visitInsn(Opcodes.LRETURN);
		cmv.visitMaxs(5, 7);
		cmv.visitEnd();
		cw.visitEnd();

		Class<?> callerClass = pluginLoader.define("hope.magic.test.DirectBootstrapCaller", cw.toByteArray());
		Method runM = callerClass.getMethod("runBenchmark", Object.class, Object.class, int.class);
		// Warmup
		runM.invoke(null, rawHiddenInvoker, pluginInstance, 200_000);
		long dStart = System.nanoTime();
		runM.invoke(null, rawHiddenInvoker, pluginInstance, iterations);
		double dTimeMs = (System.nanoTime() - dStart) / 1_000_000.0;
		System.out.printf("PLAN B (Direct Bootstrap Interface) invokeInt2: %.2f ms (%.0f ops/ms)%n",
			dTimeMs, iterations / dTimeMs);

		return new WeakReference<?>[]{
			new WeakReference<>(pluginLoader),
			new WeakReference<>(pluginClass),
			new WeakReference<>(hiddenClass),
			new WeakReference<>(invoker)
		};
	}

	@Test
	public void testPlanBHiddenClassInvoker() throws Throwable {
		WeakReference<?>[] refs = exercisePlanB();
		WeakReference<?> loaderRef = refs[0];
		WeakReference<?> classRef = refs[1];
		WeakReference<?> hiddenRef = refs[2];
		WeakReference<?> invokerRef = refs[3];

		boolean collected = false;
		for (int i = 0; i < 50; i++) {
			System.gc();
			if (loaderRef.get() == null && hiddenRef.get() == null) {
				collected = true;
				break;
			}
			Thread.sleep(20);
		}

		System.out.println("PluginClassLoader after GC: " + loaderRef.get());
		System.out.println("HiddenClass after GC: " + hiddenRef.get());
		System.out.println("Invoker after GC: " + invokerRef.get());

		Assertions.assertTrue(collected, "Both PluginClassLoader and HiddenClass must be collected by GC!");
	}

	private WeakReference<?>[] exercisePlanC() throws Throwable {
		// Target class in custom ClassLoader with a PRIVATE method
		SimpleClassLoader pluginLoader = new SimpleClassLoader(getClass().getClassLoader());
		ClassWriter targetCw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		targetCw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "hope/magic/test/NestmateTarget", null, "java/lang/Object", null);

		MethodVisitor initMv = targetCw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		initMv.visitCode();
		initMv.visitVarInsn(Opcodes.ALOAD, 0);
		initMv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		initMv.visitInsn(Opcodes.RETURN);
		initMv.visitMaxs(1, 1);
		initMv.visitEnd();

		// PRIVATE method!
		MethodVisitor mulMv = targetCw.visitMethod(Opcodes.ACC_PRIVATE, "multiply", "(II)I", null, null);
		mulMv.visitCode();
		mulMv.visitVarInsn(Opcodes.ILOAD, 1);
		mulMv.visitVarInsn(Opcodes.ILOAD, 2);
		mulMv.visitInsn(Opcodes.IMUL);
		mulMv.visitInsn(Opcodes.IRETURN);
		mulMv.visitMaxs(2, 3);
		mulMv.visitEnd();
		targetCw.visitEnd();

		Class<?> pluginClass = pluginLoader.define("hope.magic.test.NestmateTarget", targetCw.toByteArray());
		Object pluginInstance = pluginClass.getDeclaredConstructor().newInstance();

		// Generate Hidden Class as NESTMATE of pluginClass
		// It directly calls private multiply using native invokevirtual!
		String hiddenClassName = "hope/magic/test/NestmateTarget$$NestmateInvoker";
		ClassWriter hw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		hw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, hiddenClassName, null, "java/lang/Object",
			new String[]{ org.objectweb.asm.Type.getInternalName(MagicJIT.MagicInvoker.class) });

		MethodVisitor hInit = hw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		hInit.visitCode();
		hInit.visitVarInsn(Opcodes.ALOAD, 0);
		hInit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		hInit.visitInsn(Opcodes.RETURN);
		hInit.visitMaxs(1, 1);
		hInit.visitEnd();

		// invokeInt2: native direct invokevirtual on private method of nestmate!
		MethodVisitor hInvoke = hw.visitMethod(Opcodes.ACC_PUBLIC, "invokeInt2", "(Ljava/lang/Object;II)I", null, new String[]{"java/lang/Throwable"});
		hInvoke.visitCode();
		hInvoke.visitVarInsn(Opcodes.ALOAD, 1);
		hInvoke.visitTypeInsn(Opcodes.CHECKCAST, "hope/magic/test/NestmateTarget");
		hInvoke.visitVarInsn(Opcodes.ILOAD, 2);
		hInvoke.visitVarInsn(Opcodes.ILOAD, 3);
		hInvoke.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "hope/magic/test/NestmateTarget", "multiply", "(II)I", false);
		hInvoke.visitInsn(Opcodes.IRETURN);
		hInvoke.visitMaxs(3, 4);
		hInvoke.visitEnd();

		// invoke2
		MethodVisitor hInvoke2 = hw.visitMethod(Opcodes.ACC_PUBLIC, "invoke2", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
		hInvoke2.visitCode();
		hInvoke2.visitVarInsn(Opcodes.ALOAD, 0);
		hInvoke2.visitVarInsn(Opcodes.ALOAD, 1);
		hInvoke2.visitVarInsn(Opcodes.ALOAD, 2);
		hInvoke2.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
		hInvoke2.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
		hInvoke2.visitVarInsn(Opcodes.ALOAD, 3);
		hInvoke2.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
		hInvoke2.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
		hInvoke2.visitMethodInsn(Opcodes.INVOKEVIRTUAL, hiddenClassName, "invokeInt2", "(Ljava/lang/Object;II)I", false);
		hInvoke2.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
		hInvoke2.visitInsn(Opcodes.ARETURN);
		hInvoke2.visitMaxs(4, 4);
		hInvoke2.visitEnd();

		// invoke
		MethodVisitor hInvokeArr = hw.visitMethod(Opcodes.ACC_PUBLIC, "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
		hInvokeArr.visitCode();
		hInvokeArr.visitVarInsn(Opcodes.ALOAD, 0);
		hInvokeArr.visitVarInsn(Opcodes.ALOAD, 1);
		hInvokeArr.visitVarInsn(Opcodes.ALOAD, 2);
		hInvokeArr.visitInsn(Opcodes.ICONST_0);
		hInvokeArr.visitInsn(Opcodes.AALOAD);
		hInvokeArr.visitVarInsn(Opcodes.ALOAD, 2);
		hInvokeArr.visitInsn(Opcodes.ICONST_1);
		hInvokeArr.visitInsn(Opcodes.AALOAD);
		hInvokeArr.visitMethodInsn(Opcodes.INVOKEVIRTUAL, hiddenClassName, "invoke2", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
		hInvokeArr.visitInsn(Opcodes.ARETURN);
		hInvokeArr.visitMaxs(5, 3);
		hInvokeArr.visitEnd();
		hw.visitEnd();

		// Define as NESTMATE of pluginClass!
		java.lang.invoke.MethodHandles.Lookup targetLookup = java.lang.invoke.MethodHandles.privateLookupIn(pluginClass, Magic.lookup);
		java.lang.invoke.MethodHandles.Lookup nestmateLookup = targetLookup.defineHiddenClass(hw.toByteArray(), true,
			java.lang.invoke.MethodHandles.Lookup.ClassOption.NESTMATE);
		Class<?> nestmateHiddenClass = nestmateLookup.lookupClass();

		MagicJIT.MagicInvoker invoker = (MagicJIT.MagicInvoker) nestmateHiddenClass.getDeclaredConstructor().newInstance();

		int res = invoker.invokeInt2(pluginInstance, 6, 7);
		Assertions.assertEquals(42, res);

		// Benchmark C2 inline performance!
		for (int i = 0; i < 200_000; i++) {
			invoker.invokeInt2(pluginInstance, i, 2);
		}
		int iterations = 10_000_000;
		long start = System.nanoTime();
		long sum = 0;
		for (int i = 0; i < iterations; i++) {
			sum += invoker.invokeInt2(pluginInstance, i, 2);
		}
		double timeMs = (System.nanoTime() - start) / 1_000_000.0;
		System.out.printf("PLAN C (Nestmate HiddenClass) invokeInt2: %.2f ms (%.0f ops/ms)%n",
			timeMs, iterations / timeMs);

		return new WeakReference<?>[]{
			new WeakReference<>(pluginLoader),
			new WeakReference<>(pluginClass),
			new WeakReference<>(nestmateHiddenClass),
			new WeakReference<>(invoker)
		};
	}

	@Test
	public void testPlanCNestmateHiddenClass() throws Throwable {
		WeakReference<?>[] refs = exercisePlanC();
		WeakReference<?> loaderRef = refs[0];
		WeakReference<?> classRef = refs[1];
		WeakReference<?> hiddenRef = refs[2];
		WeakReference<?> invokerRef = refs[3];

		boolean collected = false;
		for (int i = 0; i < 50; i++) {
			System.gc();
			if (loaderRef.get() == null && hiddenRef.get() == null) {
				collected = true;
				break;
			}
			Thread.sleep(20);
		}

		System.out.println("Plan C PluginClassLoader after GC: " + loaderRef.get());
		System.out.println("Plan C HiddenClass after GC: " + hiddenRef.get());
		System.out.println("Plan C Invoker after GC: " + invokerRef.get());

		Assertions.assertTrue(collected, "Both PluginClassLoader and Nestmate HiddenClass must be collected by GC!");
	}

	public static class Point {
		public int x;
		public int y;
	}
}
