package hope.magic.js.runtime;

import java.util.concurrent.CompletableFuture;

public class AsyncExecutionState {
	public static final ThreadLocal<AsyncExecutionState> CURRENT = new ThreadLocal<>();

	public final JSContext cx;
	public final JSPromise returnPromise;
	private final CompletableFuture<Void> firstSuspendOrDone;
	private boolean firstSuspended = false;

	public AsyncExecutionState(JSContext cx, JSPromise returnPromise, CompletableFuture<Void> firstSuspendOrDone) {
		this.cx = cx;
		this.returnPromise = returnPromise;
		this.firstSuspendOrDone = firstSuspendOrDone;
	}

	public synchronized void onFirstAwait() {
		if (!firstSuspended) {
			firstSuspended = true;
			firstSuspendOrDone.complete(null);
		}
	}

	public synchronized void onDone() {
		if (!firstSuspended) {
			firstSuspended = true;
			firstSuspendOrDone.complete(null);
		}
	}
}
