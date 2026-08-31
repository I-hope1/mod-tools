package hope.magic.example;

import hope.magic.js.runtime.JSLinker;
import hope.magic.runtime.LinkerHelper;
import hope.magic.runtime.Magic;
import org.openjdk.jmh.annotations.*;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class DynamicInvokerBenchmark {

	private TargetObject target;
	private Object[] args;

	private MethodHandle rawMh;
	private MethodHandle adaptedMh;
	private MethodHandle asSpreaderMh;
	private MethodHandle spreadInvokerMh;

	@Setup(Level.Trial)
	public void setup() throws Exception {
		target = MagicAccessorSample.newTargetObject(12345, "Invoker Test");
		args = new Object[]{ 6, 7 };

		// 获取私有方法 multiply(int, int)
		Method m = TargetObject.class.getDeclaredMethod("multiply", int.class, int.class);
		m.setAccessible(true);
		rawMh = Magic.lookup.unreflect(m); // (TargetObject, int, int) -> int

		// 构造带参数自适应的通用 MethodHandle: (Object target, Object a, Object b) -> Object
		MethodHandle mh = rawMh;
		mh = MethodHandles.filterArguments(mh, 1, JSLinker.getArgumentFilter(int.class), JSLinker.getArgumentFilter(int.class));
		adaptedMh = mh.asType(MethodType.methodType(Object.class, Object.class, Object.class, Object.class));

		// 方案 B-1: mh.asSpreader: (Object target, Object[] args) -> Object
		asSpreaderMh = adaptedMh.asSpreader(Object[].class, 2);

		// 方案 B-2: spreadInvoker: (MethodHandle mh, Object target, Object[] args) -> Object
		MethodType genericType = MethodType.methodType(Object.class, Object.class, Object.class, Object.class);
		spreadInvokerMh = MethodHandles.spreadInvoker(genericType, 1);

		// 方案 C: 动态运行时生成的 linkToSpecial JIT 存根
		dynamicLinkToMh = createDynamicLinkToInvoker();

		// 方案 D: 动态继承 MagicAccessorImpl (MAGICIMPL) 的原生 invokespecial 存根
		magicAccessorInvoker = createMagicAccessorInvoker();
	}

	public interface FastMagicInvoker {
		Object invoke(Object target, Object[] args);
	}

	private FastMagicInvoker magicAccessorInvoker;

	private static FastMagicInvoker createMagicAccessorInvoker() throws Exception {
		Magic.install();
		org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
		String className = "hope/magic/gen/MagicInvoker_" + System.nanoTime();
		cw.visit(
			org.objectweb.asm.Opcodes.V1_8,
			org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_FINAL,
			className,
			null,
			"hope/magic/runtime/MAGICIMPL",
			new String[]{ "hope/magic/example/DynamicInvokerBenchmark$FastMagicInvoker" }
		);

		// <init>
		org.objectweb.asm.MethodVisitor initMv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		initMv.visitCode();
		initMv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
		initMv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "hope/magic/runtime/MAGICIMPL", "<init>", "()V", false);
		initMv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
		initMv.visitMaxs(0, 0);
		initMv.visitEnd();

		// Object invoke(Object target, Object[] args)
		org.objectweb.asm.MethodVisitor invokeMv = cw.visitMethod(
			org.objectweb.asm.Opcodes.ACC_PUBLIC,
			"invoke",
			"(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
			null,
			null
		);
		invokeMv.visitCode();
		invokeMv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
		invokeMv.visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, "hope/magic/example/TargetObject");

		// arg 0 -> int
		invokeMv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 2);
		invokeMv.visitInsn(org.objectweb.asm.Opcodes.ICONST_0);
		invokeMv.visitInsn(org.objectweb.asm.Opcodes.AALOAD);
		invokeMv.visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, "java/lang/Number");
		invokeMv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);

		// arg 1 -> int
		invokeMv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 2);
		invokeMv.visitInsn(org.objectweb.asm.Opcodes.ICONST_1);
		invokeMv.visitInsn(org.objectweb.asm.Opcodes.AALOAD);
		invokeMv.visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, "java/lang/Number");
		invokeMv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);

		// 原生 invokespecial 直调私有方法 multiply(II)I
		invokeMv.visitMethodInsn(
			org.objectweb.asm.Opcodes.INVOKESPECIAL,
			"hope/magic/example/TargetObject",
			"multiply",
			"(II)I",
			false
		);

		invokeMv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
		invokeMv.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
		invokeMv.visitMaxs(0, 0);
		invokeMv.visitEnd();

		cw.visitEnd();
		byte[] bytes = cw.toByteArray();
		Class<?> clazz = Magic.defineClass(DynamicInvokerBenchmark.class.getClassLoader(), bytes);
		return (FastMagicInvoker) clazz.getDeclaredConstructor().newInstance();
	}

	private MethodHandle dynamicLinkToMh;

	private static MethodHandle createDynamicLinkToInvoker() throws Exception {
		Class<?> memberNameClass = Class.forName("java.lang.invoke.MemberName");
		Object mn = LinkerHelper.resolveMemberName(TargetObject.class, "multiply", int.class, new Class<?>[]{ int.class, int.class }, (byte) 7);

		MethodHandle linkToSpecial = Magic.lookup.findStatic(
			MethodHandle.class,
			"linkToSpecial",
			MethodType.methodType(int.class, Object.class, int.class, int.class, memberNameClass)
		);

		return MethodHandles.insertArguments(linkToSpecial, 3, mn);
	}

	// ==================== 1. Java Direct 原生基准 ====================

	@Benchmark
	public int test_0_java_direct() {
		return MagicAccessorSample.callMultiply(target, 6, 7);
	}

	// ==================== 2. invokeWithArguments (低速通用路径) ====================

	@Benchmark
	public Object test_1_invokeWithArguments() throws Throwable {
		Object[] fullArgs = new Object[]{ target, args[0], args[1] };
		return adaptedMh.invokeWithArguments(fullArgs);
	}

	// ==================== 3. 方案 1: switch (args.length) + mh.invoke(...) ====================

	@Benchmark
	public Object test_2_switch_invoke() throws Throwable {
		return fastInvoke(adaptedMh, target, args);
	}

	public static Object fastInvoke(MethodHandle mh, Object receiver, Object[] args) throws Throwable {
		switch (args.length) {
			case 0: return mh.invoke(receiver);
			case 1: return mh.invoke(receiver, args[0]);
			case 2: return mh.invoke(receiver, args[0], args[1]);
			case 3: return mh.invoke(receiver, args[0], args[1], args[2]);
			default: return mh.invokeWithArguments(prepend(receiver, args));
		}
	}

	private static Object[] prepend(Object receiver, Object[] args) {
		Object[] res = new Object[args.length + 1];
		res[0] = receiver;
		System.arraycopy(args, 0, res, 1, args.length);
		return res;
	}

	// ==================== 4. 方案 B-1: MethodHandle.asSpreader(...) ====================

	@Benchmark
	public Object test_3_asSpreader() throws Throwable {
		return asSpreaderMh.invokeExact((Object) target, args);
	}

	// ==================== 5. 方案 B-2: MethodHandles.spreadInvoker(...) ====================

	@Benchmark
	public Object test_4_spreadInvoker() throws Throwable {
		return spreadInvokerMh.invokeExact(adaptedMh, (Object) target, args);
	}

	// ==================== 6. 方案 C: 动态 ASM JIT 编译 linkToSpecial 存根 ====================

	@Benchmark
	public int test_5_dynamic_linkTo_stub() throws Throwable {
		return (int) dynamicLinkToMh.invokeExact((Object) target, 6, 7);
	}

	// ==================== 7. 方案 D: 动态 MagicAccessorImpl (MAGICIMPL) 原生 invokespecial ====================

	@Benchmark
	public Object test_6_magic_accessor_impl() {
		return magicAccessorInvoker.invoke(target, args);
	}
}
