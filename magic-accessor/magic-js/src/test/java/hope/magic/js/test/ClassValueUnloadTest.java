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

			if (i == 0) {
				methodBridgesAfterFirst = MagicJIT.getMethodBridgeCacheSize();
				ctorBridgesAfterFirst = MagicJIT.getCtorBridgeCacheSize();
			} else {
				Assertions.assertEquals(methodBridgesAfterFirst, MagicJIT.getMethodBridgeCacheSize(),
					"Method bridge cache must not grow when invoking identical signature on new classes!");
				Assertions.assertEquals(ctorBridgesAfterFirst, MagicJIT.getCtorBridgeCacheSize(),
					"Constructor bridge cache must not grow when invoking identical signature on new classes!");
			}
		}
	}

	public static class Point {
		public int x;
		public int y;
	}
}
