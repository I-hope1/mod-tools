import nipx.LambdaAligner;
import nipx.ref.UpdateRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.tools.*;
import java.io.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 方法引用透明转发桩、标准适配放宽与幽灵退役防护测试。
 * <p>
 * 覆盖场景契约：
 * <ol>
 *   <li><b>用例 1</b>：{@code () -> this.save()} 变更为 {@code this::save}，非 UpdateRef 调用：无缝转发，产生副作用；</li>
 *   <li><b>用例 2</b>：同上场景，但在 UpdateRef 保护下触发：精准抛出 {@link NoSuchMethodError} 驱动局部熔断；</li>
 *   <li><b>用例 3</b>：{@code () -> this.save()} 被真实彻底删除：不转发，保持计数器不变；</li>
 *   <li><b>用例 4</b>：第二轮热更继续推进：识别器自动跳过上一轮转发桩前缀，继续维持转发，不退化为默认值空壳；</li>
 *   <li><b>用例 5</b>：同一次修改中目标方法亦被删除：安全回退为默认值空壳，绝不崩溃抛出链接错误；</li>
 *   <li><b>用例 6</b>：装箱适配：{@code Supplier<Integer>} ← {@code int a()}，转发并返回装箱后的对象；</li>
 *   <li><b>用例 7</b>：丢弃返回值适配：{@code Runnable} ← {@code int a()}，转发并安全执行（POP/POP2）；</li>
 *   <li><b>用例 8</b>：拆箱适配与 NPE 语义：{@code Function<Integer, Integer>} ← {@code int inc(int)}，转发成功且传 null 抛出 NPE；</li>
 *   <li><b>用例 9</b>：逆序传参槽位校验：{@code (a, b) -> foo(b, a)} 改变时不当成纯委托转发；</li>
 *   <li><b>用例 10</b>：带适配的转发桩在第二轮热更中再次退休：前缀跳过与适配白名单叠加，继续正常转发；</li>
 *   <li><b>用例 11</b>：类中已有方法引用，另一处 Lambda 被彻底删除：净增判据识别 {@code newRefs <= oldRefs}，阻断误转发；</li>
 *   <li><b>用例 12</b>：包含 {@code long} / {@code double} 两槽位形参的委托转发：槽位按 +2 累加严格校验，正常转发；</li>
 *   <li><b>用例 13</b>：捕获外部接收者的委托转发：{@code obj::foo}（{@code lambda$} 为 static，receiver 在槽位 0），正常转发；</li>
 *   <li><b>用例 14</b>：{@code private} 目标透明转发：覆盖 JDK 11+（JEP 181）的 {@code INVOKEVIRTUAL} 与 JDK 10 及以下的 {@code INVOKESPECIAL} 两种形态；</li>
 *   <li><b>用例 15</b>：构造器引用与 super 引用非纯委托拒绝：识别器拒绝带 {@code NEW} / {@code DUP} 的构造器委托，安全回退为空壳；</li>
 *   <li><b>用例 16</b>：{@code onOrphanInvoked} 日志去重与转发去重：连续多次调用仅首次记录，防止刷屏。</li>
 * </ol>
 * </p>
 */
public class MethodRefForwardTest {

	@BeforeEach
	@AfterEach
	void resetState() {
		LambdaAligner.clearLoggedOrphans();
		LambdaAligner.setOrphanPolicy(LambdaAligner.OrphanPolicy.SMART_ADAPTIVE);
	}

	private static final String SRC_V1 = """
		package fixture;
		public class TestTarget {
			public static int counter = 0;
			public void save() {
				++counter;
			}
			public Runnable getAction() {
				return () -> this.save();
			}
		}
		""";

	private static final String SRC_V2_METHOD_REF = """
		package fixture;
		public class TestTarget {
			public static int counter = 0;
			public void save() {
				++counter;
			}
			public Runnable getAction() {
				return this::save;
			}
		}
		""";

	private static final String SRC_V3_DELETED = """
		package fixture;
		public class TestTarget {
			public static int counter = 0;
			public void save() {
				++counter;
			}
			public Runnable getAction() {
				return null;
			}
		}
		""";

	private static final String SRC_V4_EVOLVE = """
		package fixture;
		public class TestTarget {
			public static int counter = 0;
			public void save() {
				++counter;
			}
			public Runnable getAction() {
				return this::save;
			}
			public void unrelatedEvolveMethod() {}
		}
		""";

	private static final String SRC_V5_TARGET_DELETED = """
		package fixture;
		public class TestTarget {
			public static int counter = 0;
			public Runnable getAction() {
				return null;
			}
		}
		""";

	@Test
	void test1_forwardWhenChangedToMethodRef() throws Exception {
		byte[] v1Bytes = compileSingle("fixture.TestTarget", SRC_V1);
		byte[] v2Bytes = compileSingle("fixture.TestTarget", SRC_V2_METHOD_REF);

		byte[] aligned = LambdaAligner.align(v1Bytes, v2Bytes);
		assertNotNull(aligned);

		ByteLoader loader = new ByteLoader(Map.of("fixture.TestTarget", aligned));
		Class<?> clazz = loader.loadClass("fixture.TestTarget");
		Object instance = clazz.getDeclaredConstructor().newInstance();

		resetCounter(clazz);

		Method orphanMethod = findSyntheticMethod(clazz, "lambda$getAction$0");
		assertNotNull(orphanMethod, "旧合成方法必须以桩形式复活保留");
		orphanMethod.setAccessible(true);

		// 非 UpdateRef 普通业务调用：应透明转发至 this.save()
		orphanMethod.invoke(instance);
		assertEquals(1, getCounter(clazz), "转发应当调用 save() 并使副作用 counter 递增为 1");
	}

	@Test
	void test2_throwNoSuchMethodWhenCalledByUpdateRef() throws Exception {
		byte[] v1Bytes = compileSingle("fixture.TestTarget", SRC_V1);
		byte[] v2Bytes = compileSingle("fixture.TestTarget", SRC_V2_METHOD_REF);

		byte[] aligned = LambdaAligner.align(v1Bytes, v2Bytes);
		assertNotNull(aligned);

		ByteLoader loader = new ByteLoader(Map.of("fixture.TestTarget", aligned));
		Class<?> clazz = loader.loadClass("fixture.TestTarget");
		Object instance = clazz.getDeclaredConstructor().newInstance();

		resetCounter(clazz);

		Method orphanMethod = findSyntheticMethod(clazz, "lambda$getAction$0");
		assertNotNull(orphanMethod);
		orphanMethod.setAccessible(true);

		// 模拟由 UpdateRef 持有的老 CallSite 桩（通过 MethodHandle 代理进入桩方法，中间穿透代理透明帧）
		MethodHandle mh = MethodHandles.lookup().unreflect(orphanMethod).bindTo(instance);
		Runnable callSiteRunnable = MethodHandleProxies.asInterfaceInstance(Runnable.class, mh);

		boolean[] onRemoveCalled = {false};
		Runnable wrapped = UpdateRef.wrap(callSiteRunnable, () -> onRemoveCalled[0] = true);

		// 调用 wrapped.run()，应当由 UpdateRef 捕获内部抛出的 NoSuchMethodError 触发熔断
		wrapped.run();
		UpdateRef.flushDeferredRemovals();
		assertTrue(onRemoveCalled[0], "UpdateRef 必须收到 NoSuchMethodError 触发 onRemove 熔断");
		assertNull(((UpdateRef.WrappedRef) wrapped).getOriginal(), "UpdateRef 熔断后原始引用必须被置空");
		assertEquals(0, getCounter(clazz), "受 UpdateRef 拦截时，转发不应产生副作用");
	}

	@Test
	void test3_noForwardWhenLambdaDeleted() throws Exception {
		byte[] v1Bytes = compileSingle("fixture.TestTarget", SRC_V1);
		byte[] v3Bytes = compileSingle("fixture.TestTarget", SRC_V3_DELETED);

		byte[] aligned = LambdaAligner.align(v1Bytes, v3Bytes);
		assertNotNull(aligned);

		ByteLoader loader = new ByteLoader(Map.of("fixture.TestTarget", aligned));
		Class<?> clazz = loader.loadClass("fixture.TestTarget");
		Object instance = clazz.getDeclaredConstructor().newInstance();

		resetCounter(clazz);

		Method orphanMethod = findSyntheticMethod(clazz, "lambda$getAction$0");
		assertNotNull(orphanMethod, "删除后仍必须生成幽灵空壳以兜底老 CallSite");
		orphanMethod.setAccessible(true);

		// 真删除场景：无对应方法引用，不应转发，保持计数器不变
		orphanMethod.invoke(instance);
		assertEquals(0, getCounter(clazz), "真删除场景下计数器绝不自增（走默认空壳）");
	}

	@Test
	void test4_keepForwardingInSubsequentHotSwapRounds() throws Exception {
		byte[] v1Bytes = compileSingle("fixture.TestTarget", SRC_V1);
		byte[] v2Bytes = compileSingle("fixture.TestTarget", SRC_V2_METHOD_REF);

		// 第一轮：对齐生成带转发桩的 alignedV2
		byte[] alignedV2 = LambdaAligner.align(v1Bytes, v2Bytes);

		// 第二轮：以上一轮实际生效的 alignedV2 为 oldBytes，与新演进代码 v4Bytes 对齐
		byte[] v4Bytes = compileSingle("fixture.TestTarget", SRC_V4_EVOLVE);
		byte[] alignedV4 = LambdaAligner.align(alignedV2, v4Bytes);
		assertNotNull(alignedV4);

		ByteLoader loader = new ByteLoader(Map.of("fixture.TestTarget", alignedV4));
		Class<?> clazz = loader.loadClass("fixture.TestTarget");
		Object instance = clazz.getDeclaredConstructor().newInstance();

		resetCounter(clazz);

		Method orphanMethod = findSyntheticMethod(clazz, "lambda$getAction$0");
		assertNotNull(orphanMethod, "多轮演进后老方法名仍必须保留为桩");
		orphanMethod.setAccessible(true);

		// 验证：跳过上一轮桩前缀后，依然准确识别出委托，并在新类仍有方法引用时继续转发！
		orphanMethod.invoke(instance);
		assertEquals(1, getCounter(clazz), "第二轮演进后转发桩仍应正常转发，计数器递增为 1");
	}

	@Test
	void test5_fallbackToDummyWhenTargetMethodDeleted() throws Exception {
		byte[] v1Bytes = compileSingle("fixture.TestTarget", SRC_V1);
		byte[] v5Bytes = compileSingle("fixture.TestTarget", SRC_V5_TARGET_DELETED);

		byte[] aligned = LambdaAligner.align(v1Bytes, v5Bytes);
		assertNotNull(aligned);

		ByteLoader loader = new ByteLoader(Map.of("fixture.TestTarget", aligned));
		Class<?> clazz = loader.loadClass("fixture.TestTarget");
		Object instance = clazz.getDeclaredConstructor().newInstance();

		resetCounter(clazz);

		Method orphanMethod = findSyntheticMethod(clazz, "lambda$getAction$0");
		assertNotNull(orphanMethod, "目标方法亦被删除时老方法仍保留为空壳");
		orphanMethod.setAccessible(true);

		// 目标方法被删：无法转发，安全返回，绝不抛出 NoSuchMethodError 崩溃
		orphanMethod.invoke(instance);
		assertEquals(0, getCounter(clazz), "目标方法缺失时计数器保持 0");
	}

	// ── 补充测试 6~10 ──

	private static final String SRC_V6_BOXING = """
		package fixture;
		import java.util.function.Supplier;
		public class BoxTarget {
			public static int counter = 0;
			public int a() { return ++counter; }
			public Supplier<Integer> get() { return () -> this.a(); }
		}
		""";

	private static final String SRC_V6_BOXING_REF = """
		package fixture;
		import java.util.function.Supplier;
		public class BoxTarget {
			public static int counter = 0;
			public int a() { return ++counter; }
			public Supplier<Integer> get() { return this::a; }
		}
		""";

	@Test
	void test6_boxingAdaptationForwarding() throws Exception {
		byte[] v1 = compileSingle("fixture.BoxTarget", SRC_V6_BOXING);
		byte[] v2 = compileSingle("fixture.BoxTarget", SRC_V6_BOXING_REF);

		byte[] aligned = LambdaAligner.align(v1, v2);
		assertNotNull(aligned);

		ByteLoader loader = new ByteLoader(Map.of("fixture.BoxTarget", aligned));
		Class<?> clazz = loader.loadClass("fixture.BoxTarget");
		Object instance = clazz.getDeclaredConstructor().newInstance();
		resetCounter(clazz);

		Method m = findSyntheticMethod(clazz, "lambda$get$0");
		assertNotNull(m);
		m.setAccessible(true);

		Object ret = m.invoke(instance);
		assertEquals(1, ret, "装箱适配转发应返回 Integer 1");
		assertEquals(1, getCounter(clazz));
	}

	private static final String SRC_V7_POP = """
		package fixture;
		public class PopTarget {
			public static int counter = 0;
			public int a() { return ++counter; }
			public Runnable get() { return () -> this.a(); }
		}
		""";

	private static final String SRC_V7_POP_REF = """
		package fixture;
		public class PopTarget {
			public static int counter = 0;
			public int a() { return ++counter; }
			public Runnable get() { return this::a; }
		}
		""";

	@Test
	void test7_discardReturnValuePopForwarding() throws Exception {
		byte[] v1 = compileSingle("fixture.PopTarget", SRC_V7_POP);
		byte[] v2 = compileSingle("fixture.PopTarget", SRC_V7_POP_REF);

		byte[] aligned = LambdaAligner.align(v1, v2);
		assertNotNull(aligned);

		ByteLoader loader = new ByteLoader(Map.of("fixture.PopTarget", aligned));
		Class<?> clazz = loader.loadClass("fixture.PopTarget");
		Object instance = clazz.getDeclaredConstructor().newInstance();
		resetCounter(clazz);

		Method m = findSyntheticMethod(clazz, "lambda$get$0");
		assertNotNull(m);
		m.setAccessible(true);

		m.invoke(instance);
		assertEquals(1, getCounter(clazz), "丢弃返回值适配转发应当执行副作用");
	}

	private static final String SRC_V8_UNBOX = """
		package fixture;
		import java.util.function.Function;
		public class UnboxTarget {
			public int inc(int x) { return x + 1; }
			public Function<Integer, Integer> get() { return x -> this.inc(x); }
		}
		""";

	private static final String SRC_V8_UNBOX_REF = """
		package fixture;
		import java.util.function.Function;
		public class UnboxTarget {
			public int inc(int x) { return x + 1; }
			public Function<Integer, Integer> get() { return this::inc; }
		}
		""";

	@Test
	void test8_unboxingAndNpeSemantics() throws Exception {
		byte[] v1 = compileSingle("fixture.UnboxTarget", SRC_V8_UNBOX);
		byte[] v2 = compileSingle("fixture.UnboxTarget", SRC_V8_UNBOX_REF);

		byte[] aligned = LambdaAligner.align(v1, v2);
		assertNotNull(aligned);

		ByteLoader loader = new ByteLoader(Map.of("fixture.UnboxTarget", aligned));
		Class<?> clazz = loader.loadClass("fixture.UnboxTarget");
		Object instance = clazz.getDeclaredConstructor().newInstance();

		Method m = findSyntheticMethod(clazz, "lambda$get$0");
		assertNotNull(m);
		m.setAccessible(true);

		// 正常传入 Integer(5) -> 返回 6
		Object res = m.invoke(instance, Integer.valueOf(5));
		assertEquals(6, res, "拆箱适配转发应返回 6");

		// 传入 null -> 应当抛出 NPE（符合 LMF 拆箱规范）
		InvocationTargetException ex = assertThrows(InvocationTargetException.class, () -> m.invoke(instance, (Object) null));
		assertInstanceOf(NullPointerException.class, ex.getCause(), "拆箱 null 必须抛出 NullPointerException");
	}

	private static final String SRC_V9_REVERSE_SLOT = """
		package fixture;
		import java.util.function.BiFunction;
		public class SlotTarget {
			public int sub(int a, int b) { return a - b; }
			public BiFunction<Integer, Integer, Integer> get() {
				return (a, b) -> this.sub(b, a);
			}
		}
		""";

	private static final String SRC_V9_OTHER = """
		package fixture;
		import java.util.function.BiFunction;
		public class SlotTarget {
			public int sub(int a, int b) { return a - b; }
			public BiFunction<Integer, Integer, Integer> get() {
				return null;
			}
		}
		""";

	@Test
	void test9_reverseSlotOrderRejected() throws Exception {
		byte[] v1 = compileSingle("fixture.SlotTarget", SRC_V9_REVERSE_SLOT);
		byte[] v2 = compileSingle("fixture.SlotTarget", SRC_V9_OTHER);

		byte[] aligned = LambdaAligner.align(v1, v2);
		assertNotNull(aligned);

		ByteLoader loader = new ByteLoader(Map.of("fixture.SlotTarget", aligned));
		Class<?> clazz = loader.loadClass("fixture.SlotTarget");
		Object instance = clazz.getDeclaredConstructor().newInstance();

		Method m = findSyntheticMethod(clazz, "lambda$get$0");
		assertNotNull(m);
		m.setAccessible(true);

		// 逆序传参不是严格纯委托，被槽位校验拒绝，降级为默认空壳（返回 null）
		Object res = m.invoke(instance, 10, 2);
		assertNull(res, "逆序传参不被误判为纯委托，必须降级返回空壳 null");
	}

	private static final String SRC_V10_EVOLVE = """
		package fixture;
		import java.util.function.Supplier;
		public class BoxTarget {
			public static int counter = 0;
			public int a() { return ++counter; }
			public Supplier<Integer> get() { return this::a; }
			public void unrelatedNewMethod() {}
		}
		""";

	@Test
	void test10_adaptedForwardStubRetiresAgain() throws Exception {
		byte[] v1 = compileSingle("fixture.BoxTarget", SRC_V6_BOXING);
		byte[] v2 = compileSingle("fixture.BoxTarget", SRC_V6_BOXING_REF);

		// 第一轮：生成带装箱适配转发桩的 alignedV2
		byte[] alignedV2 = LambdaAligner.align(v1, v2);

		// 第二轮：以上一轮为 oldBytes 再次对齐
		byte[] v3 = compileSingle("fixture.BoxTarget", SRC_V10_EVOLVE);
		byte[] alignedV3 = LambdaAligner.align(alignedV2, v3);
		assertNotNull(alignedV3);

		ByteLoader loader = new ByteLoader(Map.of("fixture.BoxTarget", alignedV3));
		Class<?> clazz = loader.loadClass("fixture.BoxTarget");
		Object instance = clazz.getDeclaredConstructor().newInstance();
		resetCounter(clazz);

		Method m = findSyntheticMethod(clazz, "lambda$get$0");
		assertNotNull(m);
		m.setAccessible(true);

		// 验证：跳过前缀 + 适配白名单叠加，仍能正确识别并继续转发
		Object ret = m.invoke(instance);
		assertEquals(1, ret, "第二轮演进后带适配转发桩仍应正常转发");
		assertEquals(1, getCounter(clazz));
	}

	// ── 补充测试 11~16 ──

	private static final String SRC_V11_EXISTING_REF = """
		package fixture;
		public class NetIncreaseTarget {
			public static int counter = 0;
			public void save() { ++counter; }
			public Runnable existingRef() { return this::save; }
			public Runnable lambdaToDelete() { return () -> this.save(); }
		}
		""";

	private static final String SRC_V11_LAMBDA_DELETED = """
		package fixture;
		public class NetIncreaseTarget {
			public static int counter = 0;
			public void save() { ++counter; }
			public Runnable existingRef() { return this::save; }
			public Runnable lambdaToDelete() { return null; }
		}
		""";

	@Test
	void test11_existingMethodRefDoesNotTriggerFalseForward() throws Exception {
		byte[] v1 = compileSingle("fixture.NetIncreaseTarget", SRC_V11_EXISTING_REF);
		byte[] v2 = compileSingle("fixture.NetIncreaseTarget", SRC_V11_LAMBDA_DELETED);

		byte[] aligned = LambdaAligner.align(v1, v2);
		assertNotNull(aligned);

		ByteLoader loader = new ByteLoader(Map.of("fixture.NetIncreaseTarget", aligned));
		Class<?> clazz = loader.loadClass("fixture.NetIncreaseTarget");
		Object instance = clazz.getDeclaredConstructor().newInstance();
		resetCounter(clazz);

		// 旧类中 lambdaToDelete 编译出来的合成方法为孤儿，在新类中被幽灵化
		Method orphanMethod = findSyntheticMethod(clazz, "lambda$lambdaToDelete$");
		assertNotNull(orphanMethod, "被删除的 Lambda 必须注入幽灵空壳");
		orphanMethod.setAccessible(true);

		orphanMethod.invoke(instance);
		assertEquals(0, getCounter(clazz), "类中已有方法引用、另一处 Lambda 被删时，句柄无净增，严禁误转发！");
	}

	private static final String SRC_V12_LONG_DOUBLE = """
		package fixture;
		public class SlotWidthTarget {
			public static long result = 0;
			public void calc(long a, int b, double c) {
				result = a + b + (long) c;
			}
			public interface TriConsumer {
				void accept(long a, int b, double c);
			}
			public TriConsumer get() {
				return (a, b, c) -> this.calc(a, b, c);
			}
		}
		""";

	private static final String SRC_V12_LONG_DOUBLE_REF = """
		package fixture;
		public class SlotWidthTarget {
			public static long result = 0;
			public void calc(long a, int b, double c) {
				result = a + b + (long) c;
			}
			public interface TriConsumer {
				void accept(long a, int b, double c);
			}
			public TriConsumer get() {
				return this::calc;
			}
		}
		""";

	@Test
	void test12_longDoubleParameterSlotForwarding() throws Exception {
		Map<String, byte[]> v1Units = compileUnits("fixture.SlotWidthTarget", SRC_V12_LONG_DOUBLE);
		Map<String, byte[]> v2Units = compileUnits("fixture.SlotWidthTarget", SRC_V12_LONG_DOUBLE_REF);
		byte[] v1 = v1Units.get("fixture.SlotWidthTarget");
		byte[] v2 = v2Units.get("fixture.SlotWidthTarget");

		byte[] aligned = LambdaAligner.align(v1, v2);
		assertNotNull(aligned);

		Map<String, byte[]> loaded = new HashMap<>(v1Units);
		loaded.put("fixture.SlotWidthTarget", aligned);
		ByteLoader loader = new ByteLoader(loaded);
		Class<?> clazz = loader.loadClass("fixture.SlotWidthTarget");
		Object instance = clazz.getDeclaredConstructor().newInstance();

		Method m = findSyntheticMethod(clazz, "lambda$get$0");
		assertNotNull(m);
		m.setAccessible(true);

		// (this:slot0, a:slot1-2, b:slot3, c:slot4-5) 严格按槽位顺序与宽度累加
		m.invoke(instance, 100L, 20, 3.5);
		assertEquals(123L, clazz.getField("result").getLong(null), "long/double 形参槽位累加正确且转发成功");
	}

	private static final String SRC_V13_CAPTURED_RECEIVER = """
		package fixture;
		public class CaptureReceiverTarget {
			public static int counter = 0;
			public static class Worker {
				public void doWork() { ++counter; }
			}
			public Runnable get(Worker w) {
				return () -> w.doWork();
			}
		}
		""";

	private static final String SRC_V13_CAPTURED_RECEIVER_REF = """
		package fixture;
		public class CaptureReceiverTarget {
			public static int counter = 0;
			public static class Worker {
				public void doWork() { ++counter; }
			}
			public Runnable get(Worker w) {
				return w::doWork;
			}
		}
		""";

	@Test
	void test13_capturedReceiverForwarding() throws Exception {
		Map<String, byte[]> v1Units = compileUnits("fixture.CaptureReceiverTarget", SRC_V13_CAPTURED_RECEIVER);
		Map<String, byte[]> v2Units = compileUnits("fixture.CaptureReceiverTarget", SRC_V13_CAPTURED_RECEIVER_REF);
		byte[] v1 = v1Units.get("fixture.CaptureReceiverTarget");
		byte[] v2 = v2Units.get("fixture.CaptureReceiverTarget");

		byte[] aligned = LambdaAligner.align(v1, v2);
		assertNotNull(aligned);

		Map<String, byte[]> loaded = new HashMap<>(v1Units);
		loaded.put("fixture.CaptureReceiverTarget", aligned);
		ByteLoader loader = new ByteLoader(loaded);
		Class<?> clazz = loader.loadClass("fixture.CaptureReceiverTarget");
		Class<?> workerClass = loader.loadClass("fixture.CaptureReceiverTarget$Worker");
		Object instance = clazz.getDeclaredConstructor().newInstance();
		Object worker = workerClass.getDeclaredConstructor().newInstance();
		resetCounter(clazz);

		Method m = findSyntheticMethod(clazz, "lambda$get$0");
		assertNotNull(m);
		m.setAccessible(true);

		// lambda$get$0(Worker) 为静态方法或包含 Worker 作为参数，receiver 位于参数槽位
		if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
			m.invoke(null, worker);
		} else {
			m.invoke(instance, worker);
		}
		assertEquals(1, getCounter(clazz), "捕获接收者的委托成功转发到 Worker.doWork");
	}

	private static final String SRC_V14_PRIVATE_TARGET = """
		package fixture;
		public class PrivateTarget {
			public static int counter = 0;
			private void privSave() { ++counter; }
			public Runnable get() { return () -> this.privSave(); }
		}
		""";

	private static final String SRC_V14_PRIVATE_TARGET_REF = """
		package fixture;
		public class PrivateTarget {
			public static int counter = 0;
			private void privSave() { ++counter; }
			public Runnable get() { return this::privSave; }
		}
		""";

	@Test
	void test14_privateTargetForwardingUnderVirtualAndSpecialInvocations() throws Exception {
		byte[] v1 = compileSingle("fixture.PrivateTarget", SRC_V14_PRIVATE_TARGET);
		byte[] v2 = compileSingle("fixture.PrivateTarget", SRC_V14_PRIVATE_TARGET_REF);

		// 1. 验证常规当前编译器编译物（JDK 11+ 生成 REF_invokeVirtual，JDK 8 生成 REF_invokeSpecial）
		byte[] aligned = LambdaAligner.align(v1, v2);
		assertNotNull(aligned);

		ByteLoader loader = new ByteLoader(Map.of("fixture.PrivateTarget", aligned));
		Class<?> clazz = loader.loadClass("fixture.PrivateTarget");
		Object instance = clazz.getDeclaredConstructor().newInstance();
		resetCounter(clazz);

		Method m = findSyntheticMethod(clazz, "lambda$get$0");
		assertNotNull(m);
		m.setAccessible(true);
		m.invoke(instance);
		assertEquals(1, getCounter(clazz), "private 目标方法引用应成功转发");

		// 2. 跨形态验证：通过 ASM 将 v1 中的 INVOKEVIRTUAL 改写为 INVOKESPECIAL（模拟跨 JDK 版本的不同字节码形态）
		org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(v1);
		org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
		cr.accept(cn, 0);
		for (org.objectweb.asm.tree.MethodNode mn : cn.methods) {
			if (mn.name.startsWith("lambda$get$")) {
				for (org.objectweb.asm.tree.AbstractInsnNode insn : mn.instructions) {
					if (insn.getOpcode() == org.objectweb.asm.Opcodes.INVOKEVIRTUAL) {
						((org.objectweb.asm.tree.MethodInsnNode) insn).setOpcode(org.objectweb.asm.Opcodes.INVOKESPECIAL);
					}
				}
			}
		}
		org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
		cn.accept(cw);
		byte[] v1Special = cw.toByteArray();

		byte[] alignedSpecial = LambdaAligner.align(v1Special, v2);
		assertNotNull(alignedSpecial);
		ByteLoader loaderSpecial = new ByteLoader(Map.of("fixture.PrivateTarget", alignedSpecial));
		Class<?> clazzSpecial = loaderSpecial.loadClass("fixture.PrivateTarget");
		Object instanceSpecial = clazzSpecial.getDeclaredConstructor().newInstance();
		resetCounter(clazzSpecial);

		Method mSpecial = findSyntheticMethod(clazzSpecial, "lambda$get$0");
		assertNotNull(mSpecial);
		mSpecial.setAccessible(true);
		mSpecial.invoke(instanceSpecial);
		assertEquals(1, getCounter(clazzSpecial), "旧字节码为 INVOKESPECIAL 形态时同样无缝转发");
	}

	private static final String SRC_V15_CTOR_REF = """
		package fixture;
		import java.util.function.Supplier;
		public class ConstructorTarget {
			public static class Item {
				public Item() {}
			}
			public Supplier<Item> get() {
				return () -> new Item();
			}
		}
		""";

	private static final String SRC_V15_CTOR_REF_NEW = """
		package fixture;
		import java.util.function.Supplier;
		public class ConstructorTarget {
			public static class Item {
				public Item() {}
			}
			public Supplier<Item> get() {
				return Item::new;
			}
		}
		""";

	@Test
	void test15_constructorReferenceRejectedAndFallbackToDummy() throws Exception {
		Map<String, byte[]> v1Units = compileUnits("fixture.ConstructorTarget", SRC_V15_CTOR_REF);
		Map<String, byte[]> v2Units = compileUnits("fixture.ConstructorTarget", SRC_V15_CTOR_REF_NEW);
		byte[] v1 = v1Units.get("fixture.ConstructorTarget");
		byte[] v2 = v2Units.get("fixture.ConstructorTarget");

		byte[] aligned = LambdaAligner.align(v1, v2);
		assertNotNull(aligned);

		Map<String, byte[]> loaded = new HashMap<>(v1Units);
		loaded.put("fixture.ConstructorTarget", aligned);
		ByteLoader loader = new ByteLoader(loaded);
		Class<?> clazz = loader.loadClass("fixture.ConstructorTarget");
		Object instance = clazz.getDeclaredConstructor().newInstance();

		Method m = findSyntheticMethod(clazz, "lambda$get$0");
		assertNotNull(m, "旧 Lambda 必须生成兜底桩");
		m.setAccessible(true);

		// 构造器创建包含 NEW 与 DUP 指令，不在纯委托识别器白名单内，应安全拒绝转发并返回空壳默认值 null
		Object res = m.invoke(instance);
		assertNull(res, "构造器引用委托应被安全拒绝并不转发，返回空壳 null");
	}

	@Test
	void test16_onOrphanInvokedLogDeduplication() throws Exception {
		LambdaAligner.clearLoggedOrphans();
		assertEquals(0, LambdaAligner.getLoggedOrphanCount());
		assertEquals(0, LambdaAligner.getLoggedForwardCount());

		String orphanLoc = "com.example.Test#orphan()V";
		String forwardLoc = "com.example.Test#forward()V";

		// 连续调用 5 次普通孤儿
		for (int i = 0; i < 5; i++) {
			LambdaAligner.onOrphanInvoked(orphanLoc, false);
		}
		assertEquals(1, LambdaAligner.getLoggedOrphanCount(), "普通孤儿在 LOGGED_ORPHANS 中必须严格去重为 1 条记录");
		assertTrue(LambdaAligner.isOrphanLogged(orphanLoc));

		// 连续调用 5 次转发孤儿
		for (int i = 0; i < 5; i++) {
			LambdaAligner.onOrphanInvoked(forwardLoc, true);
		}
		assertEquals(1, LambdaAligner.getLoggedForwardCount(), "转发孤儿在 LOGGED_FORWARDS 中必须严格去重为 1 条记录");
		assertTrue(LambdaAligner.isForwardLogged(forwardLoc));
	}

	private static Method findSyntheticMethod(Class<?> clazz, String prefix) {
		for (Method m : clazz.getDeclaredMethods()) {
			if (m.getName().startsWith(prefix)) return m;
		}
		return null;
	}

	private static void resetCounter(Class<?> clazz) throws Exception {
		clazz.getField("counter").setInt(null, 0);
	}

	private static int getCounter(Class<?> clazz) throws Exception {
		return clazz.getField("counter").getInt(null);
	}

	private static byte[] compileSingle(String className, String source) {
		return compileUnits(className, source).get(className);
	}

	private static Map<String, byte[]> compileUnits(String mainClassName, String source) {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) throw new IllegalStateException("System Java Compiler not available");

		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		StandardJavaFileManager standard = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);

		Map<String, byte[]> out = new HashMap<>();
		JavaFileManager memory = new ForwardingJavaFileManager<StandardJavaFileManager>(standard) {
			@Override
			public JavaFileObject getJavaFileForOutput(Location location, String name,
			                                           JavaFileObject.Kind kind, FileObject sibling) {
				URI uri = URI.create("mem:///" + name.replace('.', '/') + kind.extension);
				return new SimpleJavaFileObject(uri, kind) {
					@Override
					public OutputStream openOutputStream() {
						return new ByteArrayOutputStream() {
							@Override
							public void close() throws IOException {
								super.close();
								out.put(name, toByteArray());
							}
						};
					}
				};
			}
		};

		URI uri = URI.create("string:///" + mainClassName.replace('.', '/') + ".java");
		JavaFileObject unit = new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
			@Override
			public CharSequence getCharContent(boolean ignoreEncodingErrors) {
				return source;
			}
		};

		JavaCompiler.CompilationTask task = compiler.getTask(null, memory, diagnostics,
			List.of("-nowarn"), null, List.of(unit));
		boolean success = Boolean.TRUE.equals(task.call());
		if (!success) {
			StringBuilder msg = new StringBuilder("Compilation failed for " + mainClassName + ":\n");
			for (Diagnostic<?> d : diagnostics.getDiagnostics()) {
				msg.append(d.toString()).append("\n");
			}
			throw new IllegalStateException(msg.toString());
		}

		return out;
	}

	static final class ByteLoader extends ClassLoader {
		private final Map<String, byte[]> classes;

		ByteLoader(Map<String, byte[]> classes) {
			super(MethodRefForwardTest.class.getClassLoader());
			this.classes = classes;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}
}
