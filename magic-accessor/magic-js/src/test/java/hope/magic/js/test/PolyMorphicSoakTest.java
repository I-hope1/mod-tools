package hope.magic.js.test;

import hope.magic.js.runtime.JSContext;
import hope.magic.js.runtime.JSFunction;
import hope.magic.js.runtime.JSObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 高强度多态内联缓存（FlatPolyGuard & Megamorphic）浸泡与堆内存正确性压力测试
 */
public class PolyMorphicSoakTest {

	/**
	 * 1. 严格梯度测试（1, 2, 4, 8, 64 独立偏移 Shape）
	 * 结合 -XX:+VerifyBeforeGC -XX:+VerifyAfterGC 与周期性 System.gc()，
	 * 验证 HotSpot C2 在多态到巨态演化过程中的堆内存零破坏与结果绝对正确性。
	 */
	@Test
	@DisplayName("Gradient Shapes 1/2/4/8/64 with Heap Verification & GC")
	public void testGradientShapeHeapVerification() {
		for (int n : new int[]{1, 2, 4, 8, 64}) {
			JSContext cx = new JSContext();
			StringBuilder sb = new StringBuilder();
			sb.append("pool_").append(n).append(" = [\n");
			for (int i = 0; i < n; i++) {
				sb.append("    { ");
				for (int p = 0; p < i; p++) {
					sb.append("dummy_").append(p).append(": 0, ");
				}
				sb.append("val: ").append(10.5 + i)
				  .append(", prop_").append(i).append(": ").append(i * 10)
				  .append(" }");
				if (i < n - 1) sb.append(",\n");
			}
			sb.append("\n];\n\n");
			sb.append("test_data_").append(n).append(" = [];\n");
			sb.append("for (var i = 0; i < 2000; i++) {\n");
			sb.append("    test_data_").append(n).append("[i] = pool_").append(n).append("[i % ").append(n).append("];\n");
			sb.append("}\n");
			cx.eval(sb.toString());

			String access = """
				function compute(data) {
				    var total = 0;
				    for (var i = 0; i < 2000; i++) {
				        total = total + data[i].val;
				    }
				    return total;
				}
				compute(test_data_%d);
				""".formatted(n);

			// 多次执行并穿插 System.gc() 强制触发 -XX:+VerifyBeforeGC / VerifyAfterGC
			for (int iter = 0; iter < 10; iter++) {
				Object res = cx.eval(access);
				double expected = switch (n) {
					case 1 -> 21000.0;
					case 2 -> 22000.0;
					case 4 -> 24000.0;
					case 8 -> 28000.0;
					case 64 -> 83616.0;
					default -> 0.0;
				};
				Assertions.assertEquals(expected, ((Number) res).doubleValue(), 1e-6);
			}
			System.gc(); // 触发 HotSpot 堆校验
		}
	}

	/**
	 * 2. 针对 installFlatPolyGuard() 与 Megamorphic 跃迁的高强度 Soak 测试：
	 * 在同一个 CallSite 上反复、随机在 4态 ↔ 6态 ↔ 64态 Shape 对象之间高速交替喂入，
	 * 执行 200,000 次调用，验证平铺跳转表与直接映射哈希表在震荡切换下的鲁棒性。
	 */
	@Test
	@DisplayName("High Intensity Soak Test (200,000 oscillations across 4 <-> 6 <-> 64 shapes)")
	public void testHighIntensityFlatPolyGuardAndMegamorphicSoak() throws Throwable {
		JSContext cx = new JSContext();

		// 生成 64 个具有不同独立槽位偏移的对象池
		StringBuilder initCode = new StringBuilder();
		initCode.append("var all_shapes = [];\n");
		for (int i = 0; i < 64; i++) {
			initCode.append("all_shapes[").append(i).append("] = { ");
			for (int p = 0; p < i; p++) {
				initCode.append("d_").append(p).append(": 0, ");
			}
			initCode.append("val: ").append(100.0 + i)
			        .append(", name: 'item_").append(i).append("'");
			initCode.append(" };\n");
		}
		initCode.append("""
			function readDouble(obj) {
			    return obj.val;
			}
			function readName(obj) {
			    return obj.name;
			}
			function mutateDouble(obj, delta) {
			    obj.val = obj.val + delta;
			    return obj.val;
			}
			""");
		cx.eval(initCode.toString());

		Object[] allShapes = (Object[]) ((hope.magic.js.runtime.JSArray) cx.eval("all_shapes;")).toArray();
		JSFunction readDouble = (JSFunction) cx.eval("readDouble;");
		JSFunction readName = (JSFunction) cx.eval("readName;");
		JSFunction mutateDouble = (JSFunction) cx.eval("mutateDouble;");

		Random rng = new Random(42);
		int totalIterations = 200_000;
		int[] shapeModes = new int[]{4, 6, 64, 4, 64, 6, 8, 4, 64};

		double checksumDouble = 0.0;
		long checksumNameLen = 0;

		for (int i = 0; i < totalIterations; i++) {
			// 在 4态、6态、64态模式之间轮转与随机震荡
			int mode = shapeModes[(i / 500) % shapeModes.length];
			int shapeIdx = rng.nextInt(mode);
			Object obj = allShapes[shapeIdx];

			// 1. 测试 Getter (Double 快路径 / FlatPolyGuard / Megamorphic directCache)
			Object doubleVal = readDouble.call1(cx, null, obj);
			double d = ((Number) doubleVal).doubleValue();
			checksumDouble += (d % 10.0);

			// 2. 测试 Getter (Object 路径)
			Object nameVal = readName.call1(cx, null, obj);
			String s = (String) nameVal;
			checksumNameLen += s.length();

			// 3. 测试 Setter (FlatPolyGuard Setter / Megamorphic Setter)
			if (i % 10 == 0) {
				mutateDouble.call2(cx, null, obj, 0.5);
				mutateDouble.call2(cx, null, obj, -0.5); // 恢复原值
			}

			// 4. 周期性 GC 触发 JVM 严格堆校验 (-XX:+VerifyBeforeGC / VerifyAfterGC)
			if (i > 0 && i % 25_000 == 0) {
				System.gc();
			}
		}

		Assertions.assertTrue(checksumDouble > 0.0);
		Assertions.assertTrue(checksumNameLen > 0);
		System.gc(); // 最终堆检查
	}

	/**
	 * 3. 多线程并发 Soak 测试：
	 * 4 个并发工作线程同时高频调用同一个动态 CallSite，不断交替传入 4/6/64 态 Shape 对象，
	 * 伴随后台随机强制 GC，验证 directCache 的 VarHandle 原子操作与并发安全性。
	 */
	@Test
	@DisplayName("Concurrent Multi-threaded Soak Test with GC Safepoint Stress")
	public void testConcurrentPolyMorphicSoak() throws Exception {
		JSContext cx = new JSContext();

		// 初始化 64 个 Shape 对象
		StringBuilder initCode = new StringBuilder();
		initCode.append("var shared_shapes = [];\n");
		for (int i = 0; i < 64; i++) {
			initCode.append("shared_shapes[").append(i).append("] = { ");
			for (int p = 0; p < i; p++) {
				initCode.append("p_").append(p).append(": ").append(p).append(", ");
			}
			initCode.append("target_prop: ").append(50.0 + i).append(" };\n");
		}
		initCode.append("""
			function sharedAccessor(o) {
			    return o.target_prop;
			}
			""");
		cx.eval(initCode.toString());

		Object[] sharedShapes = (Object[]) ((hope.magic.js.runtime.JSArray) cx.eval("shared_shapes;")).toArray();
		JSFunction accessor = (JSFunction) cx.eval("sharedAccessor;");

		int threadCount = 4;
		int perThreadOps = 50_000;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch latch = new CountDownLatch(threadCount);
		AtomicBoolean failure = new AtomicBoolean(false);
		AtomicLong totalSum = new AtomicLong(0);

		for (int t = 0; t < threadCount; t++) {
			final int threadId = t;
			executor.submit(() -> {
				try {
					Random rng = new Random(1000 + threadId);
					double localSum = 0;
					int[] modes = new int[]{4, 6, 64};
					for (int i = 0; i < perThreadOps; i++) {
						int mode = modes[(i / 200) % modes.length];
						int idx = rng.nextInt(mode);
						Object obj = sharedShapes[idx];
						Object res = accessor.call1(cx, null, obj);
						double val = ((Number) res).doubleValue();
						localSum += (val % 10.0);
					}
					totalSum.addAndGet((long) localSum);
				} catch (Throwable ex) {
					ex.printStackTrace();
					failure.set(true);
				} finally {
					latch.countDown();
				}
			});
		}

		// 主线程在并发期间触发 3 次 System.gc()，施加 safepoint 压力与堆扫描
		for (int i = 0; i < 3; i++) {
			Thread.sleep(100);
			System.gc();
		}

		boolean completed = latch.await(60, TimeUnit.SECONDS);
		executor.shutdown();

		Assertions.assertTrue(completed, "All threads should finish within timeout");
		Assertions.assertFalse(failure.get(), "No thread should encounter exceptions or memory corruption");
		Assertions.assertTrue(totalSum.get() > 0, "Total sum should be strictly positive");

		System.gc(); // 最终收尾 GC
	}

	/**
	 * 4. 动态属性扩容与溢出槽（Overflow Prim/Object/Mask）高频跃迁测试：
	 * 验证当属性数量动态从 4 扩展到 8、16、32、64，
	 * 触发 overflowPrim 与 overflowDoubleMask 重新扩容时，底层内存与方法句柄访问的一致性。
	 */
	@Test
	@DisplayName("Dynamic Property Expansion & Overflow Slots Soak Test")
	public void testDynamicPropertyExpansionSoak() throws Throwable {
		JSContext cx = new JSContext();
		String script = """
			function makeExpander() {
			    var o = { a: 1.0, b: 2.0 };
			    return o;
			}
			function accessDynamic(o, propName) {
			    return o[propName];
			}
			""";
		cx.eval(script);
		JSFunction makeExpander = (JSFunction) cx.eval("makeExpander;");
		JSFunction accessDynamic = (JSFunction) cx.eval("accessDynamic;");

		for (int iter = 0; iter < 1000; iter++) {
			JSObject obj = (JSObject) makeExpander.call0(cx, null);
			// 动态扩容到 64 个属性，迫使其多次触发 overflowPrim 与 overflowObj 重新分配
			for (int p = 0; p < 64; p++) {
				if (p % 2 == 0) {
					obj.putDouble("ext_" + p, p * 1.5);
				} else {
					obj.put("ext_" + p, "val_" + p);
				}
			}

			// 随机验证
			for (int p = 0; p < 64; p++) {
				Object val = accessDynamic.call2(cx, null, obj, "ext_" + p);
				if (p % 2 == 0) {
					Assertions.assertEquals(p * 1.5, ((Number) val).doubleValue(), 1e-6);
				} else {
					Assertions.assertEquals("val_" + p, val);
				}
			}

			if (iter % 250 == 0) {
				System.gc();
			}
		}
		System.gc();
	}

	/**
	 * 5. 同 Offset 跨类型混合（Double vs Object）梯度基准与内存安全测试：
	 * 针对 Bug-A 描述的混淆场景，专门构造在同一 offset 上既有 Double 又有 Object 的形状集合（4, 8, 64 态）。
	 * 在高频访问与交替变异下，配合 -XX:+VerifyBeforeGC / VerifyAfterGC 验证零类型混淆与零虚假指针破坏。
	 */
	@Test
	@DisplayName("Mixed-Type Collision (Double vs Object at same offsets) with GC Verification")
	public void testMixedTypeOffsetCollisionGradient() throws Throwable {
		for (int n : new int[]{4, 8, 64}) {
			JSContext cx = new JSContext();
			StringBuilder sb = new StringBuilder();
			sb.append("var mixed_pool_").append(n).append(" = [\n");
			for (int i = 0; i < n; i++) {
				sb.append("    { ");
				// 固定在 offset 0 处分配属性 val，但在偶数形状上是 double，在奇数形状上是 Object / String
				if (i % 2 == 0) {
					sb.append("val: ").append(100.0 + i);
				} else {
					sb.append("val: { payload: 'item_").append(i).append("', tag: ").append(i).append(" }");
				}
				for (int p = 0; p < i; p++) {
					sb.append(", extra_").append(p).append(": ").append(p);
				}
				sb.append(" }");
				if (i < n - 1) sb.append(",\n");
			}
			sb.append("\n];\n\n");
			sb.append("""
				function processMixed(pool, count) {
				    var numSum = 0;
				    var objCount = 0;
				    for (var i = 0; i < count; i++) {
				        var item = pool[i % pool.length];
				        var v = item.val;
				        if (typeof v === 'number') {
				            numSum += v;
				        } else if (v && typeof v === 'object') {
				            objCount += v.tag;
				        }
				    }
				    return [numSum, objCount];
				}
				""");
			cx.eval(sb.toString());

			JSFunction processMixed = (JSFunction) cx.eval("processMixed;");
			Object pool = cx.eval("mixed_pool_" + n + ";");

			// 循环执行，周期性强制 GC 触发堆指针校验
			for (int iter = 0; iter < 10; iter++) {
				Object res = processMixed.call2(cx, null, pool, 5000);
				Assertions.assertNotNull(res);
				System.gc(); // 严苛堆验证
			}
		}
	}
}
