import arc.Application;
import arc.Core;
import nipx.ref.UpdateRef;
import nipx.ref.UpdateRef.CompositeAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency regression tests for UpdateRef:
 * 1. CompositeAction thread safety and deduplication under high contention.
 * 2. addOnRemove thread safety and ensuring actions added to already removed refs are deferred.
 * 3. tryScheduleDeferredFlush lock granularity and deferred removal execution.
 */
public class UpdateRefConcurrencyTest {

	private Application oldApp;

	@BeforeEach
	void setUp() {
		oldApp = Core.app;
	}

	@AfterEach
	void tearDown() {
		Core.app = oldApp;
		UpdateRef.flushDeferredRemovals();
	}

	@Test
	void testCompositeActionConcurrency() throws Exception {
		int threadCount = 16;
		int itemsPerThread = 50;
		CompositeAction composite = new CompositeAction(() -> {});

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		AtomicInteger[] counters = new AtomicInteger[threadCount * itemsPerThread];
		for (int i = 0; i < counters.length; i++) {
			counters[i] = new AtomicInteger(0);
		}

		List<Future<?>> futures = new ArrayList<>();
		for (int t = 0; t < threadCount; t++) {
			final int threadIdx = t;
			futures.add(executor.submit(() -> {
				try {
					startLatch.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				for (int i = 0; i < itemsPerThread; i++) {
					final int idx = threadIdx * itemsPerThread + i;
					Runnable r = new IndexedAction(idx, counters[idx]);
					composite.add(r);
					// Add same item again to test deduplication under concurrency
					composite.add(r);
				}
			}));
		}

		startLatch.countDown();
		for (Future<?> f : futures) {
			f.get(5, TimeUnit.SECONDS);
		}

		// Total actions: 1 initial + threadCount * itemsPerThread
		Runnable[] actions = composite.getActions();
		assertEquals(threadCount * itemsPerThread + 1, actions.length);

		// Concurrently invoke run() from multiple threads
		CountDownLatch runLatch = new CountDownLatch(1);
		List<Future<?>> runFutures = new ArrayList<>();
		for (int t = 0; t < threadCount; t++) {
			runFutures.add(executor.submit(() -> {
				try {
					runLatch.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				composite.run();
			}));
		}
		runLatch.countDown();
		for (Future<?> f : runFutures) {
			f.get(5, TimeUnit.SECONDS);
		}
		executor.shutdown();

		// Every indexed action must have executed exactly once
		for (int i = 0; i < counters.length; i++) {
			assertEquals(1, counters[i].get(), "Action " + i + " must execute exactly once");
		}
	}

	@Test
	void testAddOnRemoveConcurrency() throws Exception {
		Runnable wrapped = UpdateRef.wrap((Runnable) () -> {});
		assertTrue(wrapped instanceof UpdateRef.WrappedRef);
		UpdateRef ref = ((UpdateRef.WrappedRef) wrapped).getUpdateRef();
		assertNotNull(ref);

		int threadCount = 16;
		int itemsPerThread = 50;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		AtomicInteger[] counters = new AtomicInteger[threadCount * itemsPerThread];
		for (int i = 0; i < counters.length; i++) {
			counters[i] = new AtomicInteger(0);
		}

		List<Future<?>> futures = new ArrayList<>();
		for (int t = 0; t < threadCount; t++) {
			final int threadIdx = t;
			futures.add(executor.submit(() -> {
				try {
					startLatch.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				for (int i = 0; i < itemsPerThread; i++) {
					final int idx = threadIdx * itemsPerThread + i;
					IndexedAction action = new IndexedAction(idx, counters[idx]);
					boolean added = ref.addOnRemove(action);
					assertTrue(added);
					// Duplicate add
					ref.addOnRemove(action);
				}
			}));
		}

		startLatch.countDown();
		for (Future<?> f : futures) {
			f.get(5, TimeUnit.SECONDS);
		}
		executor.shutdown();

		Runnable onRemove = ref.getOnRemove();
		assertNotNull(onRemove);
		assertTrue(onRemove instanceof CompositeAction);
		CompositeAction ca = (CompositeAction) onRemove;
		assertEquals(threadCount * itemsPerThread, ca.getActions().length);

		ca.run();
		for (int i = 0; i < counters.length; i++) {
			assertEquals(1, counters[i].get(), "Action " + i + " must execute exactly once");
		}
	}

	@Test
	void testAddOnRemoveAfterTriggerRemove() throws Exception {
		AtomicInteger triggerCount = new AtomicInteger(0);
		Runnable wrapped = UpdateRef.wrap((Runnable) () -> {
			throw new NoSuchMethodError("test error");
		}, triggerCount::incrementAndGet);

		assertTrue(wrapped instanceof UpdateRef.WrappedRef);
		UpdateRef ref = ((UpdateRef.WrappedRef) wrapped).getUpdateRef();
		assertNotNull(ref);

		// Trigger linkage error to cause removal
		wrapped.run();
		assertTrue(ref.isRemoved());

		// addOnRemove on an already removed ref must return false and defer the action
		AtomicInteger lateActionExecuted = new AtomicInteger(0);
		boolean added = ref.addOnRemove(lateActionExecuted::incrementAndGet);
		assertFalse(added, "addOnRemove on removed ref must return false");

		// Flush deferred removals: both initial trigger and late action must execute
		UpdateRef.flushDeferredRemovals();
		assertEquals(1, triggerCount.get(), "Initial onRemove must execute");
		assertEquals(1, lateActionExecuted.get(), "Late action added to removed ref must be deferred and executed");
	}

	@Test
	void testTryScheduleDeferredFlushGranularity() throws Exception {
		AtomicInteger postCount = new AtomicInteger(0);
		ConcurrentLinkedQueue<Runnable> postedTasks = new ConcurrentLinkedQueue<>();
		Core.app = (Application) java.lang.reflect.Proxy.newProxyInstance(
			Application.class.getClassLoader(),
			new Class<?>[]{Application.class},
			(proxy, method, methodArgs) -> {
				if ("post".equals(method.getName()) && methodArgs != null && methodArgs.length == 1) {
					postCount.incrementAndGet();
					postedTasks.add((Runnable) methodArgs[0]);
					return null;
				}
				return null;
			}
		);

		int threadCount = 16;
		int itemsPerThread = 50;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		AtomicInteger executedCount = new AtomicInteger(0);

		Method deferMethod = UpdateRef.class.getDeclaredMethod("deferRemoval", Runnable.class);
		deferMethod.setAccessible(true);

		List<Future<?>> futures = new ArrayList<>();
		for (int t = 0; t < threadCount; t++) {
			futures.add(executor.submit(() -> {
				try {
					startLatch.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				for (int i = 0; i < itemsPerThread; i++) {
					try {
						deferMethod.invoke(null, (Runnable) executedCount::incrementAndGet);
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}
			}));
		}

		startLatch.countDown();
		for (Future<?> f : futures) {
			f.get(5, TimeUnit.SECONDS);
		}
		executor.shutdown();

		// Ensure post was called at least once
		assertTrue(postCount.get() >= 1, "Core.app.post must have been scheduled");

		// Run posted tasks (simulating main thread loop)
		while (!postedTasks.isEmpty()) {
			Runnable task = postedTasks.poll();
			if (task != null) task.run();
		}

		assertEquals(threadCount * itemsPerThread, executedCount.get(),
			"All deferred actions must be executed without loss");
	}

	private static class IndexedAction implements Runnable {
		private final int id;
		private final AtomicInteger counter;

		IndexedAction(int id, AtomicInteger counter) {
			this.id = id;
			this.counter = counter;
		}

		@Override
		public void run() {
			counter.incrementAndGet();
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o instanceof IndexedAction other) {
				return this.id == other.id;
			}
			return false;
		}

		@Override
		public int hashCode() {
			return Integer.hashCode(id);
		}
	}
}
