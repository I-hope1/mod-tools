package modtools;

import org.openjdk.jmh.annotations.*;
import test0.*;

import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static test0.Magic.lookup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class MethodBenchmark {
	static final Method       M_ADD_ONE;
	static final MethodHandle MH_ADD_ONE;

	static {
		try {
			Magic.install();
			M_ADD_ONE = TestAccess.class.getDeclaredMethod("addOne", int.class);
			M_ADD_ONE.setAccessible(true);
			MH_ADD_ONE = lookup.findStatic(TestAccess.class, "addOne",
			 MethodType.methodType(int.class, int.class));
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	public int x = 42;

	@Benchmark
	public int direct() {
		return TestAccess.addOne(x++);
	}

	@Benchmark
	public int reflectMethod() throws Exception {
		return (int) M_ADD_ONE.invoke(null, x++);
	}

	@Benchmark
	public int methodHandle() throws Throwable {
		return (int) MH_ADD_ONE.invokeExact(x++);
	}

	@Benchmark
	public int magicImpl() {
		return (int) Magic.callAddOne(x++);
	}
}