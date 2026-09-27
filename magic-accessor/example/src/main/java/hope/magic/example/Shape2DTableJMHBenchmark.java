package hope.magic.example;

import hope.magic.js.runtime.JSShape;
import hope.magic.js.runtime.SymbolTable;
import hope.magic.runtime.BootTestStableHolder;
import hope.magic.runtime.Magic;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {
	"-XX:+UnlockDiagnosticVMOptions"
})
public class Shape2DTableJMHBenchmark {

	// 常量属性 ID (模拟 obj.x 调用点，编译期已知属性 ID)
	public static final int CONST_PROP_X = 15;
	public static final int CONST_PROP_Y = 16;

	// 测试规模配置
	private static final int NUM_PROPS  = 64;
	private static final int NUM_SHAPES = 32;
	private static final int PATTERN_SIZE = 1024;

	// 普通 static final (非 @Stable 对照组)
	public static final long[][] NON_STABLE_PROP_SHAPE = new long[NUM_PROPS][NUM_SHAPES];
	public static final long[][] NON_STABLE_SHAPE_PROP = new long[NUM_SHAPES][NUM_PROPS];

	// 测试用 Shape 与访问序列
	private JSShape monoShape;
	private final JSShape[] polyShapes = new JSShape[4];
	private final JSShape[] patternShapes = new JSShape[PATTERN_SIZE];
	private final int[] patternProps = new int[PATTERN_SIZE];
	private int counter = 0;

	@Setup(Level.Trial)
	public void setup() throws Throwable {
		if (!Magic.isInstalled()) Magic.install();

		// 1. 初始化 @Stable 二维数组
		BootTestStableHolder.TABLE_PROP_SHAPE = new long[NUM_PROPS][NUM_SHAPES];
		BootTestStableHolder.TABLE_SHAPE_PROP = new long[NUM_SHAPES][NUM_PROPS];

		// 2. 构造 4 个具有重叠属性的不同 Shape (模拟 4-态 Polymorphic)
		// Shape 0: { x, y, a, b }
		// Shape 1: { a, x, c, d }
		// Shape 2: { m, n, x, y }
		// Shape 3: { p, q, r, x }
		int symA = SymbolTable.symbolId("a");
		int symB = SymbolTable.symbolId("b");
		int symC = SymbolTable.symbolId("c");
		int symD = SymbolTable.symbolId("d");
		int symM = SymbolTable.symbolId("m");
		int symN = SymbolTable.symbolId("n");
		int symP = SymbolTable.symbolId("p");
		int symQ = SymbolTable.symbolId("q");
		int symR = SymbolTable.symbolId("r");

		polyShapes[0] = JSShape.ROOT
			.addProperty(CONST_PROP_X, JSShape.TYPE_INT)
			.addProperty(CONST_PROP_Y, JSShape.TYPE_DOUBLE)
			.addProperty(symA, JSShape.TYPE_OBJECT)
			.addProperty(symB, JSShape.TYPE_OBJECT);

		polyShapes[1] = JSShape.ROOT
			.addProperty(symA, JSShape.TYPE_OBJECT)
			.addProperty(CONST_PROP_X, JSShape.TYPE_INT)
			.addProperty(symC, JSShape.TYPE_DOUBLE)
			.addProperty(symD, JSShape.TYPE_OBJECT);

		polyShapes[2] = JSShape.ROOT
			.addProperty(symM, JSShape.TYPE_OBJECT)
			.addProperty(symN, JSShape.TYPE_INT)
			.addProperty(CONST_PROP_X, JSShape.TYPE_INT)
			.addProperty(CONST_PROP_Y, JSShape.TYPE_DOUBLE);

		polyShapes[3] = JSShape.ROOT
			.addProperty(symP, JSShape.TYPE_OBJECT)
			.addProperty(symQ, JSShape.TYPE_DOUBLE)
			.addProperty(symR, JSShape.TYPE_INT)
			.addProperty(CONST_PROP_X, JSShape.TYPE_INT);

		monoShape = polyShapes[0];

		// 3. 填充二维表 (将 slot 与 type 打包为 64-bit entry)
		for (int sIdx = 0; sIdx < 4; sIdx++) {
			JSShape s = polyShapes[sIdx];
			int sId = s.id;
			for (int p = 0; p < NUM_PROPS; p++) {
				int offset = s.getOffset(p);
				long entry;
				if (offset >= 0) {
					byte type = s.getSlotType(offset);
					entry = (((long) type & 0xFF) << 32) | (offset & 0xFFFFFFFFL);
				} else {
					entry = -1L;
				}
				BootTestStableHolder.TABLE_PROP_SHAPE[p][sId] = entry;
				BootTestStableHolder.TABLE_SHAPE_PROP[sId][p] = entry;
				NON_STABLE_PROP_SHAPE[p][sId] = entry;
				NON_STABLE_SHAPE_PROP[sId][p] = entry;
			}
		}

		// 4. 构建测试访问序列 (轮换交替与伪随机散列)
		Random rnd = new Random(42);
		for (int i = 0; i < PATTERN_SIZE; i++) {
			patternShapes[i] = polyShapes[i & 3];
			patternProps[i] = (rnd.nextInt(10) < 8) ? CONST_PROP_X : rnd.nextInt(NUM_PROPS);
		}
	}

	// =========================================================================
	// 场景 1：单态 (Monomorphic) + 编译期常量属性 (obj.x 固定 Shape)
	// =========================================================================

	@Benchmark
	public void c1_mono_const_1_jsshape_baseline(Blackhole bh) {
		int offset = monoShape.getOffset(CONST_PROP_X);
		byte type = monoShape.getSlotType(offset);
		bh.consume(offset + type);
	}

	@Benchmark
	public void c1_mono_const_2_stable_prop_shape(Blackhole bh) {
		long entry = BootTestStableHolder.TABLE_PROP_SHAPE[CONST_PROP_X][monoShape.id];
		bh.consume(entry);
	}

	@Benchmark
	public void c1_mono_const_3_stable_shape_prop(Blackhole bh) {
		long entry = BootTestStableHolder.TABLE_SHAPE_PROP[monoShape.id][CONST_PROP_X];
		bh.consume(entry);
	}

	// =========================================================================
	// 场景 2：4-多态 (Polymorphic) + 编译期常量属性 (obj.x 4 种 Shape 交替轮换)
	// =========================================================================

	@Benchmark
	public void c2_poly_const_1_jsshape_baseline(Blackhole bh) {
		JSShape s = patternShapes[counter++ & (PATTERN_SIZE - 1)];
		int offset = s.getOffset(CONST_PROP_X);
		byte type = s.getSlotType(offset);
		bh.consume(offset + type);
	}

	@Benchmark
	public void c2_poly_const_2_stable_prop_shape(Blackhole bh) {
		JSShape s = patternShapes[counter++ & (PATTERN_SIZE - 1)];
		long entry = BootTestStableHolder.TABLE_PROP_SHAPE[CONST_PROP_X][s.id];
		bh.consume(entry);
	}

	@Benchmark
	public void c2_poly_const_3_stable_shape_prop(Blackhole bh) {
		JSShape s = patternShapes[counter++ & (PATTERN_SIZE - 1)];
		long entry = BootTestStableHolder.TABLE_SHAPE_PROP[s.id][CONST_PROP_X];
		bh.consume(entry);
	}

	@Benchmark
	public void c2_poly_const_4_non_stable_prop_shape(Blackhole bh) {
		JSShape s = patternShapes[counter++ & (PATTERN_SIZE - 1)];
		long entry = NON_STABLE_PROP_SHAPE[CONST_PROP_X][s.id];
		bh.consume(entry);
	}

	@Benchmark
	public void c2_poly_const_5_non_stable_shape_prop(Blackhole bh) {
		JSShape s = patternShapes[counter++ & (PATTERN_SIZE - 1)];
		long entry = NON_STABLE_SHAPE_PROP[s.id][CONST_PROP_X];
		bh.consume(entry);
	}

	// =========================================================================
	// 场景 3：4-多态 (Polymorphic) + 动态属性 (obj[dynKey] 动态属性与 Shape 混合)
	// =========================================================================

	@Benchmark
	public void c3_poly_dyn_1_jsshape_baseline(Blackhole bh) {
		int idx = counter++ & (PATTERN_SIZE - 1);
		JSShape s = patternShapes[idx];
		int prop = patternProps[idx];
		int offset = s.getOffset(prop);
		byte type = (offset >= 0) ? s.getSlotType(offset) : 0;
		bh.consume(offset + type);
	}

	@Benchmark
	public void c3_poly_dyn_2_stable_prop_shape(Blackhole bh) {
		int idx = counter++ & (PATTERN_SIZE - 1);
		JSShape s = patternShapes[idx];
		int prop = patternProps[idx];
		long entry = BootTestStableHolder.TABLE_PROP_SHAPE[prop][s.id];
		bh.consume(entry);
	}

	@Benchmark
	public void c3_poly_dyn_3_stable_shape_prop(Blackhole bh) {
		int idx = counter++ & (PATTERN_SIZE - 1);
		JSShape s = patternShapes[idx];
		int prop = patternProps[idx];
		long entry = BootTestStableHolder.TABLE_SHAPE_PROP[s.id][prop];
		bh.consume(entry);
	}

	public static void main(String[] args) throws Exception {
		Options opt = new OptionsBuilder()
			.include(Shape2DTableJMHBenchmark.class.getSimpleName())
			.build();
		new Runner(opt).run();
	}
}
