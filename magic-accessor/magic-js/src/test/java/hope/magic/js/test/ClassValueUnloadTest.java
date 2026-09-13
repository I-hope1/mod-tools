package hope.magic.js.test;

import hope.magic.js.runtime.MagicJIT;
import hope.magic.js.runtime.MethodResolver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
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

		return new WeakReference<>(loader);
	}

	@Test
	public void testClassLoaderCanBeUnloadedAfterReflectionAndJITCaching() throws Throwable {
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
}
