package modtools;

import org.openjdk.jmh.annotations.*;
import test0.*;

import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import static test0.Magic.lookup;


@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class FieldBenchmark {
	static final VarHandle VH_XP;
	static final Field     F_XP;

	static {
		try {
			Magic.install();
			VH_XP = lookup.findStaticVarHandle(TestAccess.class, "xp", int.class);
			F_XP = TestAccess.class.getDeclaredField("xp");
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	@Benchmark
	public void directField() {
		TestAccess.xp = TestAccess.xp + 1;
	}

	@Benchmark
	public void varHandle() {
		VH_XP.set((int) VH_XP.get() + 1);
	}

	@Benchmark
	public void magicImpl() {
		Magic.setXp(Magic.getXp() + 1);
	}

	@Benchmark
	public void reflectField() {
		try {
			F_XP.setInt(null, F_XP.getInt(null) + 1);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
