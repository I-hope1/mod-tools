package modtools.struct.v6;

import arc.util.*;
import modtools.ModTools;
import modtools.utils.SR.CatchSR;

import java.util.concurrent.*;

public interface AThreads {
	AThreads impl = ModTools.isV6 ? new V6() : new V7();
	ExecutorService boundedExecutor(@Nullable String name, int max);

	class V6 implements AThreads {
		public ExecutorService boundedExecutor(String name, int max) {
			return new ThreadPoolExecutor(1, max, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>(), r -> newThread(r, name, true));
		}
	}
	class V7 implements AThreads {
		public ExecutorService boundedExecutor(String name, int max) {
			return new ThreadPoolExecutor(1, max, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>(), r -> newThread(r, name, true));
		}
	}

	ThreadFactory factory = CatchSR.apply(() ->
	 CatchSR.of(() -> Thread.ofVirtual().factory())
		.get(() -> Thread::new));
	private static Thread newThread(Runnable r, @Nullable String name, boolean daemon) {
		Thread thread = factory.newThread(r);
		if (name != null) thread.setName(name);

		thread.setDaemon(daemon);
		thread.setUncaughtExceptionHandler((t, e) -> Log.err(e));
		return thread;
	}
}