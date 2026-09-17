package hope.magic.example;

import hope.magic.runtime.Magic;
import hope.magic.annotation.AccessMode;
import hope.magic.js.runtime.MagicJIT;
import hope.magic.js.runtime.MagicJIT.MagicConstructorInvoker;
import hope.magic.js.runtime.MagicJIT.MagicInvoker;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
// 修正点 2: 提升到 3 次 Fork，并挂载 GC Profiler 与底层模块穿透参数
@Fork(value = 3, warmups = 1, jvmArgsAppend = {
    "-Xms4g", "-Xmx4g",
    "-XX:+UnlockDiagnosticVMOptions"
})
public class MagicJITBenchmark {

    public static class BenchmarkTarget {
        private final int id;
        private final String name;

        public BenchmarkTarget(int id, String name) {
            this.id = id;
            this.name = name;
        }

        private int multiply(int a, int b) {
            return a * b;
        }

        public static int directMultiply(BenchmarkTarget target, int a, int b) {
            return target.multiply(a, b);
        }
    }

    public BenchmarkTarget targetInstance;
    public int argA = 6;
    public int argB = 7;
    public String ctorString = "benchmark";

    // 用于模拟“未逃逸分析”下的调用入参
    public Object[] methodArgs;
    public Object[] ctorArgs;

    // 反射
    public Method reflectMethod;
    public Constructor<?> reflectCtor;

    // MethodHandle 家族
    public MethodHandle mhBoundExact;    // 修正点 1: 标记为闭包绑定特例
    public MethodHandle mhUnboundExact;  // 修正点 1: 真实的 3 参数未绑定通用 Handle
    public MethodHandle mhSpreader;
    public MethodHandle mhCtor;

    // MagicJIT
    public MagicInvoker linkToInvoker;
    public MagicConstructorInvoker linkToCtorInvoker;
    public MethodHandle exactMethodStub;

    // MagicJIT (MAGIC_ACCESSOR 模式对比)
    public MagicInvoker accessorInvoker;
    public MagicConstructorInvoker accessorCtorInvoker;

    // MagicJIT (NESTMATE 模式对比)
    public MagicInvoker nestmateInvoker;
    public MagicConstructorInvoker nestmateCtorInvoker;

    // MagicJIT (UNSAFE_AND_METHODHANDLE 模式对比)
    public MagicInvoker mhFallbackInvoker;

    @Setup(Level.Trial)
    public void setup() throws Throwable {
        if (!Magic.isInstalled()) {
            Magic.install();
        }
        targetInstance = new BenchmarkTarget(1, "init");
        methodArgs = new Object[]{ argA, argB };
        ctorArgs = new Object[]{ argA, ctorString };

        // 1. 反射
        reflectMethod = BenchmarkTarget.class.getDeclaredMethod("multiply", int.class, int.class);
        reflectMethod.setAccessible(true);
        reflectCtor = BenchmarkTarget.class.getDeclaredConstructor(int.class, String.class);
        reflectCtor.setAccessible(true);

        // 2. MethodHandle
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle rawMh = lookup.unreflect(reflectMethod);

        this.mhUnboundExact = rawMh; // 签名: (BenchmarkTarget, int, int)int
        this.mhBoundExact   = rawMh.bindTo(targetInstance); // 签名: (int, int)int (闭包特例)
        this.mhSpreader     = rawMh.asSpreader(Object[].class, 2);
        this.mhCtor         = lookup.unreflectConstructor(reflectCtor);

        // 3. MagicJIT (各模式全量对比)
        linkToInvoker = MagicJIT.getMethodInvoker(
            BenchmarkTarget.class, "multiply", 2, false, AccessMode.UNSAFE_AND_LINKTO
        );
        linkToCtorInvoker = MagicJIT.getConstructorInvoker(
            BenchmarkTarget.class, 2, AccessMode.UNSAFE_AND_LINKTO
        );
        exactMethodStub = MagicJIT.getExactMethodStub(
            BenchmarkTarget.class, reflectMethod, AccessMode.UNSAFE_AND_LINKTO
        );

        accessorInvoker = MagicJIT.getMethodInvoker(
            BenchmarkTarget.class, "multiply", 2, false, AccessMode.MAGIC_ACCESSOR
        );
        accessorCtorInvoker = MagicJIT.getConstructorInvoker(
            BenchmarkTarget.class, 2, AccessMode.MAGIC_ACCESSOR
        );

        nestmateInvoker = MagicJIT.getMethodInvoker(
            BenchmarkTarget.class, "multiply", 2, false, AccessMode.NESTMATE
        );
        nestmateCtorInvoker = MagicJIT.getConstructorInvoker(
            BenchmarkTarget.class, 2, AccessMode.NESTMATE
        );

        mhFallbackInvoker = MagicJIT.getMethodInvoker(
            BenchmarkTarget.class, "multiply", 2, false, AccessMode.UNSAFE_AND_METHODHANDLE
        );
    }

    // =========================================================================
    // 【基准 1】方法调用链接开销测试
    // =========================================================================

    @Benchmark
    public int b1_01_JavaDirect() {
        return BenchmarkTarget.directMultiply(targetInstance, argA, argB);
    }

    @Benchmark
    public Object b1_02_ReflectInvoke() throws Exception {
        return reflectMethod.invoke(targetInstance, argA, argB);
    }

    // 修正点 1 & 5: 对照组 A - 未绑定的通用 MH 直调 (三参数)
    @Benchmark
    public int b1_03_MethodHandle_UnboundExact() throws Throwable {
        return (int) mhUnboundExact.invokeExact(targetInstance, argA, argB);
    }

    // 修正点 1 & 5: 对照组 B - 预绑定的闭包式 MH 直调 (二参数特例)
    @Benchmark
    public int b1_03_MethodHandle_BoundExact_SpecialCase() throws Throwable {
        return (int) mhBoundExact.invokeExact(argA, argB);
    }

    @Benchmark
    public Object b1_04_MethodHandle_Spreader() throws Throwable {
        return mhSpreader.invoke(targetInstance, methodArgs);
    }

    @Benchmark
    public Object b1_05_MagicInvoker_Array() throws Throwable {
        return linkToInvoker.invoke(targetInstance, methodArgs);
    }

    @Benchmark
    public Object b1_06_MagicInvoker_LinkTo_Invoke2_Boxed() throws Throwable {
        return linkToInvoker.invoke2(targetInstance, (Object) argA, (Object) argB);
    }

    @Benchmark
    public int b1_06_1_MagicInvoker_LinkTo_InvokeInt2_ZeroBoxing() throws Throwable {
        return linkToInvoker.invokeInt2(targetInstance, argA, argB);
    }

    @Benchmark
    public int b1_06_2_MagicInvoker_Accessor_InvokeInt2_ZeroBoxing() throws Throwable {
        return accessorInvoker.invokeInt2(targetInstance, argA, argB);
    }

    @Benchmark
    public Object b1_06_3_MagicInvoker_Accessor_Invoke2_Boxed() throws Throwable {
        return accessorInvoker.invoke2(targetInstance, (Object) argA, (Object) argB);
    }

    @Benchmark
    public Object b1_06_4_MagicInvoker_MHFallback_Invoke2() throws Throwable {
        return mhFallbackInvoker.invoke2(targetInstance, (Object) argA, (Object) argB);
    }

    @Benchmark
    public int b1_06_5_MagicInvoker_Nestmate_InvokeInt2_ZeroBoxing() throws Throwable {
        return nestmateInvoker.invokeInt2(targetInstance, argA, argB);
    }

    @Benchmark
    public Object b1_06_6_MagicInvoker_Nestmate_Invoke2_Boxed() throws Throwable {
        return nestmateInvoker.invoke2(targetInstance, (Object) argA, (Object) argB);
    }

    @Benchmark
    public Object b1_07_MagicExactMethodStub() throws Throwable {
        return exactMethodStub.invokeExact((Object) targetInstance, (Object) argA, (Object) argB);
    }

    // =========================================================================
    // 【基准 2】构造器创建测试 (配合 GC Profiler 分析分配率)
    // =========================================================================

    @Benchmark
    public Object b2_01_JavaDirectNew() {
        return new BenchmarkTarget(argA, ctorString);
    }

    @Benchmark
    public Object b2_02_ReflectNewInstance() throws Exception {
        return reflectCtor.newInstance(ctorArgs);
    }

    @Benchmark
    public Object b2_03_MethodHandleCtor() throws Throwable {
        return mhCtor.invoke(argA, ctorString);
    }

    @Benchmark
    public Object b2_04_MagicCtor_LinkTo_Array() throws Throwable {
        return linkToCtorInvoker.newInstance(ctorArgs);
    }

    @Benchmark
    public Object b2_05_MagicCtor_LinkTo_NewInstance2() throws Throwable {
        return linkToCtorInvoker.newInstance2(argA, ctorString);
    }

    @Benchmark
    public Object b2_06_MagicCtor_Accessor_NewInstance2() throws Throwable {
        return accessorCtorInvoker.newInstance2(argA, ctorString);
    }

    @Benchmark
    public Object b2_07_MagicCtor_Nestmate_NewInstance2() throws Throwable {
        return nestmateCtorInvoker.newInstance2(argA, ctorString);
    }

    // =========================================================================
    // 入口：挂载 GCProfiler 进行全方位扫描
    // =========================================================================
    public static void main(String[] args) throws Exception {
        if (args != null && args.length > 0) {
            new Runner(new org.openjdk.jmh.runner.options.CommandLineOptions(args)).run();
        } else {
            Options opt = new OptionsBuilder()
                    .include(MagicJITBenchmark.class.getSimpleName())
                    .addProfiler(GCProfiler.class) // 修正点 3: 监控每千次调用的分配速率与 GC 消耗
                    .build();
            new Runner(opt).run();
        }
    }
}