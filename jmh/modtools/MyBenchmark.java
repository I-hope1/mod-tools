package modtools;

import org.openjdk.jmh.annotations.*;
import test0.*;

import java.lang.invoke.VarHandle;
import java.util.concurrent.TimeUnit;

import static test0.Magic.lookup;


@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class MyBenchmark {
	static final VarHandle VH_XP;

	static {
		try {
			Magic.install();
			VH_XP = lookup.findStaticVarHandle(TestAccess.class, "xp", int.class);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	@Benchmark
	public void direct() {
		TestAccess.xp += 1;
	}

	@Benchmark
	public void varHandle() {
		VH_XP.set((int) VH_XP.get() + 1);
	}

	@Benchmark
	public void magicImpl() {
		Magic.setXp(Magic.getXp() + 1);
	}
}
