package hope.magic.example;

import hope.magic.js.runtime.FastAccessor;
import hope.magic.js.runtime.JSObject;
import hope.magic.runtime.BootStableHolder;
import hope.magic.runtime.Magic;
import jdk.internal.vm.annotation.Stable;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = {
	"-XX:+UnlockDiagnosticVMOptions"
})
@SuppressWarnings("removal")
public class JSObjectSlotAccessJMHBenchmark {

	private static final Unsafe UNSAFE = Magic.unsafe;

	private JSObject jsObj;
	private int counter = 0;

	// AppClassLoader 下的 @Stable 数组（对照组，C2 不信任此加载器上的 @Stable）
	@Stable
	public final long[] appPrimOffsets = new long[8];

	@Stable
	public final long[] appObjOffsets = new long[8];

	private static final Object DUMMY_OBJ = new Object();
	private static final Object NEW_OBJ = "benchmark_string_value";

	private static final int PATTERN_SIZE = 1024;
	private final int[] alternatingOffsets = new int[PATTERN_SIZE];
	private final int[] chaoticOffsets = new int[PATTERN_SIZE];

	@Setup(Level.Trial)
	public void setup() throws Throwable {
		if (!Magic.isInstalled()) Magic.install();

		jsObj = new JSObject();
		// 初始化 0..7 内置槽位
		for (int i = 0; i < 8; i++) {
			Field primField = JSObject.class.getField("prim" + i);
			Field objField = JSObject.class.getField("obj" + i);
			long pOff = UNSAFE.objectFieldOffset(primField);
			long oOff = UNSAFE.objectFieldOffset(objField);

			appPrimOffsets[i] = pOff;
			appObjOffsets[i] = oOff;
			BootStableHolder.JS_PRIM_OFFSETS[i] = pOff;
			BootStableHolder.JS_OBJ_OFFSETS[i] = oOff;

			jsObj.setDoubleSlot(i, (double) (i * 10 + 1));
			UNSAFE.putObject(jsObj, oOff, DUMMY_OBJ);
		}

		// 初始化 8..15 溢出槽位 (overflowPrim)
		for (int i = 8; i < 16; i++) {
			jsObj.setDoubleSlot(i, (double) (i * 10 + 1));
		}

		// 构造分支预测压力数据
		Random rnd = new Random(42);
		for (int i = 0; i < PATTERN_SIZE; i++) {
			// 1. 严格 50/50 颠簸交替：in-object(0..7) 与 overflow(8..15) 每次交替翻转
			alternatingOffsets[i] = (i & 1) == 0 ? (i & 7) : (8 + (i & 7));
			// 2. 纯伪随机散列（0..15）：彻底击溃 CPU 分支预测历史表 (TAGE/BTB)
			chaoticOffsets[i] = rnd.nextInt(16);
		}
	}

	// =========================================================================
	// 场景一：Double 槽位读取（对比 switch vs 裸 Unsafe vs 带 offset 边界检查的生产级 Unsafe）
	// =========================================================================

	@Benchmark
	public void read_double_1_switch_const_slot(Blackhole bh) {
		// 现有方案：常量下标 3，走 switch (3)
		bh.consume(jsObj.getDoubleSlot(3));
	}

	@Benchmark
	public void read_double_2_unsafe_boot_stable_const_slot(Blackhole bh) {
		// 新方案：常量下标 3，BootLoader @Stable 数组（C2 数组元素常量折叠为直接 offset）
		bh.consume(UNSAFE.getDouble(jsObj, BootStableHolder.JS_PRIM_OFFSETS[3]));
	}

	@Benchmark
	public void read_double_3_fast_accessor_direct(Blackhole bh) {
		// 理论极限基准：FastAccessor 扁平单字段直读（直接读 jsObj.prim3）
		bh.consume(FastAccessor.getSlot3Double(jsObj));
	}

	@Benchmark
	public void read_double_4_switch_dynamic_slot(Blackhole bh) {
		int idx = counter++ & 7;
		// 现有方案：动态下标轮换，触发 tableswitch 指令分发
		bh.consume(jsObj.getDoubleSlot(idx));
	}

	@Benchmark
	public void read_double_5_unsafe_raw_boot_stable_dynamic(Blackhole bh) {
		int idx = counter++ & 7;
		// 裸 Unsafe：无边界检查（理论上限基准）
		bh.consume(UNSAFE.getDouble(jsObj, BootStableHolder.JS_PRIM_OFFSETS[idx]));
	}

	@Benchmark
	public void read_double_6_unsafe_guarded_boot_stable_dynamic(Blackhole bh) {
		int idx = counter++ & 7;
		// 生产级实际落地方案：带 offset < 8 边界检查与 overflow 回退分支
		if (idx < 8) {
			bh.consume(UNSAFE.getDouble(jsObj, BootStableHolder.JS_PRIM_OFFSETS[idx]));
		} else {
			bh.consume(jsObj.getDoubleSlot(idx));
		}
	}

	// =========================================================================
	// 场景二：Object 槽位读取（现有 switch vs 生产级带边界检查的 Unsafe）
	// =========================================================================

	@Benchmark
	public void read_obj_1_switch_dynamic_slot(Blackhole bh) {
		int idx = counter++ & 7;
		bh.consume(jsObj.getRawObjectSlot(idx));
	}

	@Benchmark
	public void read_obj_2_unsafe_guarded_boot_stable_dynamic(Blackhole bh) {
		int idx = counter++ & 7;
		if (idx < 8) {
			bh.consume(UNSAFE.getObject(jsObj, BootStableHolder.JS_OBJ_OFFSETS[idx]));
		} else {
			bh.consume(jsObj.getRawObjectSlot(idx));
		}
	}

	// =========================================================================
	// 场景三：写入与读改写工作流
	// =========================================================================

	@Benchmark
	public void write_double_1_switch_dynamic_slot() {
		int idx = counter++ & 7;
		jsObj.setDoubleSlot(idx, 123.456);
	}

	@Benchmark
	public void write_double_2_unsafe_guarded_dynamic_slot() {
		int idx = counter++ & 7;
		jsObj.setDoubleMask(idx);
		if (idx < 8) {
			UNSAFE.putDouble(jsObj, BootStableHolder.JS_PRIM_OFFSETS[idx], 123.456);
			UNSAFE.putObject(jsObj, BootStableHolder.JS_OBJ_OFFSETS[idx], null);
		} else {
			jsObj.setDoubleSlot(idx, 123.456);
		}
	}

	@Benchmark
	public void workflow_1_switch_dynamic(Blackhole bh) {
		int idx = counter++ & 7;
		double v = jsObj.getDoubleSlot(idx);
		jsObj.setDoubleSlot(idx, v + 1.0);
		bh.consume(v);
	}

	@Benchmark
	public void workflow_2_unsafe_guarded_dynamic(Blackhole bh) {
		int idx = counter++ & 7;
		double v;
		if (idx < 8) {
			long pOff = BootStableHolder.JS_PRIM_OFFSETS[idx];
			v = UNSAFE.getDouble(jsObj, pOff);
			jsObj.setDoubleMask(idx);
			UNSAFE.putDouble(jsObj, pOff, v + 1.0);
			UNSAFE.putObject(jsObj, BootStableHolder.JS_OBJ_OFFSETS[idx], null);
		} else {
			v = jsObj.getDoubleSlot(idx);
			jsObj.setDoubleSlot(idx, v + 1.0);
		}
		bh.consume(v);
	}

	// =========================================================================
	// 场景四：极限分支预测压力测试（CPU Branch Misprediction Stress Test）
	// =========================================================================

	@Benchmark
	public void mispredict_1_alternating_switch(Blackhole bh) {
		// 每次访问在 in-object(0..7) 和 overflow(8..15) 之间交替，迫使分支预测颠簸
		int slot = alternatingOffsets[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(jsObj.getDoubleSlot(slot));
	}

	@Benchmark
	public void mispredict_2_alternating_unsafe_guarded(Blackhole bh) {
		int slot = alternatingOffsets[counter++ & (PATTERN_SIZE - 1)];
		if (slot < 8) {
			bh.consume(UNSAFE.getDouble(jsObj, BootStableHolder.JS_PRIM_OFFSETS[slot]));
		} else {
			bh.consume(jsObj.getDoubleSlot(slot));
		}
	}

	@Benchmark
	public void mispredict_3_chaotic_switch(Blackhole bh) {
		// 纯伪随机散列分布（0..15）：彻底打碎 CPU 的 Branch Target Buffer 与历史表
		int slot = chaoticOffsets[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(jsObj.getDoubleSlot(slot));
	}

	@Benchmark
	public void mispredict_4_chaotic_unsafe_guarded(Blackhole bh) {
		int slot = chaoticOffsets[counter++ & (PATTERN_SIZE - 1)];
		if (slot < 8) {
			bh.consume(UNSAFE.getDouble(jsObj, BootStableHolder.JS_PRIM_OFFSETS[slot]));
		} else {
			bh.consume(jsObj.getDoubleSlot(slot));
		}
	}

	public static void main(String[] args) throws Exception {
		Options opt = new OptionsBuilder()
			.include(JSObjectSlotAccessJMHBenchmark.class.getSimpleName())
			.build();
		new Runner(opt).run();
	}
}
