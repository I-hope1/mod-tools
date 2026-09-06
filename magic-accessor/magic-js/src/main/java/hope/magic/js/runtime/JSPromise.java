package hope.magic.js.runtime;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class JSPromise extends JSObject {
	public static final int PENDING   = 0;
	public static final int FULFILLED = 1;
	public static final int REJECTED  = 2;

	@FunctionalInterface
	public interface Reaction {
		Object run(Object arg) throws Throwable;
	}

	public volatile int state = PENDING;
	public volatile Object result = null;

	private final List<Consumer<Object>> fulfillReactions = new ArrayList<>();
	private final List<Consumer<Object>> rejectReactions  = new ArrayList<>();
	private final Object lock = new Object();

	public final CompletableFuture<Object> future = new CompletableFuture<>();
	public final JSContext cx;

	public JSPromise() {
		this(JSContext.current(), JSContext.LazyBuiltins.PROMISE_PROTOTYPE);
	}

	public JSPromise(JSContext cx) {
		this(cx != null ? cx : JSContext.current(), JSContext.LazyBuiltins.PROMISE_PROTOTYPE);
	}

	public JSPromise(JSContext cx, JSObject proto) {
		super(proto != null ? proto : JSContext.LazyBuiltins.PROMISE_PROTOTYPE);
		this.cx = cx != null ? cx : JSContext.current();
	}

	public boolean isPending() {
		return state == PENDING;
	}

	public boolean isFulfilled() {
		return state == FULFILLED;
	}

	public boolean isRejected() {
		return state == REJECTED;
	}

	public Object getResult() {
		return result;
	}

	public int getState() {
		return state;
	}

	public void fulfill(Object val) {
		List<Consumer<Object>> reactionsToRun;
		synchronized (lock) {
			if (state != PENDING) return;
			state = FULFILLED;
			result = val;
			reactionsToRun = new ArrayList<>(fulfillReactions);
			fulfillReactions.clear();
			rejectReactions.clear();
		}
		future.complete(val);
		for (Consumer<Object> reaction : reactionsToRun) {
			enqueueMicrotask(() -> reaction.accept(val));
		}
	}

	public void reject(Object reason) {
		List<Consumer<Object>> reactionsToRun;
		synchronized (lock) {
			if (state != PENDING) return;
			state = REJECTED;
			result = reason;
			reactionsToRun = new ArrayList<>(rejectReactions);
			fulfillReactions.clear();
			rejectReactions.clear();
		}
		Throwable err = reason instanceof Throwable t ? t : new JSOps.JSException(reason);
		future.completeExceptionally(err);
		for (Consumer<Object> reaction : reactionsToRun) {
			enqueueMicrotask(() -> reaction.accept(reason));
		}
	}

	public void resolve(Object val) {
		if (val == this) {
			reject(cx != null ? JSContext.makeTypeError("Chaining cycle detected for promise") : new RuntimeException("Chaining cycle detected for promise"));
			return;
		}
		if (val instanceof JSPromise other) {
			other.whenSettled(cx, () -> {
				if (other.state == FULFILLED) {
					resolve(other.result);
				} else {
					reject(other.result);
				}
			});
			return;
		}
		if (val instanceof JSObject obj) {
			Object thenVal;
			try {
				thenVal = obj.get("then");
			} catch (Throwable t) {
				reject(t instanceof JSOps.JSException je ? je.value : t);
				return;
			}
			if (thenVal instanceof JSFunction thenFn) {
				AtomicBoolean called = new AtomicBoolean(false);
				JSFunction resolveFn = (c, thisObj, args) -> {
					if (called.compareAndSet(false, true)) {
						resolve(args.length > 0 ? args[0] : JSUndefined.INSTANCE);
					}
					return JSUndefined.INSTANCE;
				};
				JSFunction rejectFn = (c, thisObj, args) -> {
					if (called.compareAndSet(false, true)) {
						reject(args.length > 0 ? args[0] : JSUndefined.INSTANCE);
					}
					return JSUndefined.INSTANCE;
				};
				try {
					thenFn.call(cx, obj, new Object[]{ resolveFn, rejectFn });
				} catch (Throwable t) {
					if (called.compareAndSet(false, true)) {
						reject(t instanceof JSOps.JSException je ? je.value : t);
					}
				}
				return;
			}
		}
		fulfill(val);
	}

	public JSPromise then(JSContext cx, Reaction onFulfilled, Reaction onRejected) {
		return then(cx, (Object) onFulfilled, (Object) onRejected);
	}

	public JSPromise then(JSContext cx, Object onFulfilled, Object onRejected) {
		JSContext currentCx = cx != null ? cx : (this.cx != null ? this.cx : JSContext.current());
		JSPromise nextPromise = new JSPromise(currentCx);

		Consumer<Object> fulfillAction = val -> {
			if (onFulfilled instanceof Reaction r) {
				try {
					Object res = r.run(val);
					nextPromise.resolve(res);
				} catch (Throwable t) {
					nextPromise.reject(t instanceof JSOps.JSException je ? je.value : t);
				}
			} else if (onFulfilled instanceof JSFunction fn) {
				try {
					Object res = fn.call1(currentCx, null, val);
					nextPromise.resolve(res);
				} catch (Throwable t) {
					nextPromise.reject(t instanceof JSOps.JSException je ? je.value : t);
				}
			} else {
				nextPromise.resolve(val);
			}
		};

		Consumer<Object> rejectAction = reason -> {
			if (onRejected instanceof Reaction r) {
				try {
					Object res = r.run(reason);
					nextPromise.resolve(res);
				} catch (Throwable t) {
					nextPromise.reject(t instanceof JSOps.JSException je ? je.value : t);
				}
			} else if (onRejected instanceof JSFunction fn) {
				try {
					Object res = fn.call1(currentCx, null, reason);
					nextPromise.resolve(res);
				} catch (Throwable t) {
					nextPromise.reject(t instanceof JSOps.JSException je ? je.value : t);
				}
			} else {
				nextPromise.reject(reason);
			}
		};

		synchronized (lock) {
			if (state == FULFILLED) {
				enqueueMicrotask(() -> fulfillAction.accept(result));
			} else if (state == REJECTED) {
				enqueueMicrotask(() -> rejectAction.accept(result));
			} else {
				fulfillReactions.add(fulfillAction);
				rejectReactions.add(rejectAction);
			}
		}

		return nextPromise;
	}

	public JSPromise catch_(JSContext cx, Object onRejected) {
		return then(cx, null, onRejected);
	}

	public JSPromise finally_(JSContext cx, Object onFinally) {
		JSContext currentCx = cx != null ? cx : this.cx;
		if (onFinally instanceof JSFunction fn) {
			return then(currentCx,
				val -> {
					Object finRes = fn.call0(currentCx, null);
					if (finRes instanceof JSPromise p) {
						return p.then(currentCx, ignored -> val, null);
					}
					return val;
				},
				reason -> {
					Object finRes = fn.call0(currentCx, null);
					if (finRes instanceof JSPromise p) {
						return p.then(currentCx, ignored -> {
							throw reason instanceof Throwable t ? t : new JSOps.JSException(reason);
						}, null);
					}
					throw reason instanceof Throwable t ? t : new JSOps.JSException(reason);
				}
			);
		}
		return then(currentCx, onFinally, onFinally);
	}

	public void whenSettled(JSContext cx, Runnable action) {
		Consumer<Object> wrapped = ignored -> action.run();
		synchronized (lock) {
			if (state != PENDING) {
				enqueueMicrotask(action);
			} else {
				fulfillReactions.add(wrapped);
				rejectReactions.add(wrapped);
			}
		}
	}

	private void enqueueMicrotask(Runnable task) {
		JSContext targetCx = cx != null ? cx : JSContext.current();
		if (targetCx != null) {
			targetCx.queueMicrotask(task);
		} else {
			ForkJoinPool.commonPool().execute(task);
		}
	}

	public static JSPromise resolve(JSContext cx, Object val) {
		if (val instanceof JSPromise p) {
			return p;
		}
		JSContext currentCx = cx != null ? cx : JSContext.current();
		JSPromise promise = new JSPromise(currentCx);
		promise.resolve(val);
		return promise;
	}

	public static JSPromise reject(JSContext cx, Object reason) {
		JSContext currentCx = cx != null ? cx : JSContext.current();
		JSPromise promise = new JSPromise(currentCx);
		promise.reject(reason);
		return promise;
	}

	public static JSPromise all(JSContext cx, Object iterable) {
		JSContext currentCx = cx != null ? cx : JSContext.current();
		JSPromise outPromise = new JSPromise(currentCx);
		List<Object> items = toList(iterable);
		if (items == null) {
			outPromise.reject(currentCx != null ? JSContext.makeTypeError("Promise.all requires an iterable") : new IllegalArgumentException("Not iterable"));
			return outPromise;
		}
		int count = items.size();
		if (count == 0) {
			outPromise.fulfill(new JSArray());
			return outPromise;
		}

		JSArray results = new JSArray();
		results.setLength(count);
		for (int i = 0; i < count; i++) {
			results.setElement(i, JSUndefined.INSTANCE);
		}
		AtomicInteger remaining = new AtomicInteger(count);
		AtomicBoolean done = new AtomicBoolean(false);

		for (int i = 0; i < count; i++) {
			final int index = i;
			Object item = items.get(i);
			resolve(currentCx, item).then(currentCx,
				val -> {
					results.setElement(index, val);
					if (remaining.decrementAndGet() == 0 && done.compareAndSet(false, true)) {
						outPromise.fulfill(results);
					}
					return JSUndefined.INSTANCE;
				},
				err -> {
					if (done.compareAndSet(false, true)) {
						outPromise.reject(err);
					}
					return JSUndefined.INSTANCE;
				}
			);
		}

		return outPromise;
	}

	public static JSPromise allSettled(JSContext cx, Object iterable) {
		JSContext currentCx = cx != null ? cx : JSContext.current();
		JSPromise outPromise = new JSPromise(currentCx);
		List<Object> items = toList(iterable);
		if (items == null) {
			outPromise.reject(currentCx != null ? JSContext.makeTypeError("Promise.allSettled requires an iterable") : new IllegalArgumentException("Not iterable"));
			return outPromise;
		}
		int count = items.size();
		if (count == 0) {
			outPromise.fulfill(new JSArray());
			return outPromise;
		}

		JSArray results = new JSArray();
		results.setLength(count);
		for (int i = 0; i < count; i++) {
			results.setElement(i, JSUndefined.INSTANCE);
		}
		AtomicInteger remaining = new AtomicInteger(count);

		for (int i = 0; i < count; i++) {
			final int index = i;
			Object item = items.get(i);
			resolve(currentCx, item).whenSettled(currentCx, () -> {
				JSPromise settled = resolve(currentCx, item);
				JSObject statusObj = new JSObject();
				if (settled.state == FULFILLED) {
					statusObj.put("status", "fulfilled");
					statusObj.put("value", settled.result);
				} else {
					statusObj.put("status", "rejected");
					statusObj.put("reason", settled.result);
				}
				results.setElement(index, statusObj);
				if (remaining.decrementAndGet() == 0) {
					outPromise.fulfill(results);
				}
			});
		}

		return outPromise;
	}

	public static JSPromise race(JSContext cx, Object iterable) {
		JSContext currentCx = cx != null ? cx : JSContext.current();
		JSPromise outPromise = new JSPromise(currentCx);
		List<Object> items = toList(iterable);
		if (items == null) {
			outPromise.reject(currentCx != null ? JSContext.makeTypeError("Promise.race requires an iterable") : new IllegalArgumentException("Not iterable"));
			return outPromise;
		}

		AtomicBoolean settled = new AtomicBoolean(false);
		for (Object item : items) {
			resolve(currentCx, item).then(currentCx,
				val -> {
					if (settled.compareAndSet(false, true)) {
						outPromise.fulfill(val);
					}
					return JSUndefined.INSTANCE;
				},
				err -> {
					if (settled.compareAndSet(false, true)) {
						outPromise.reject(err);
					}
					return JSUndefined.INSTANCE;
				}
			);
		}

		return outPromise;
	}

	public static JSPromise any(JSContext cx, Object iterable) {
		JSContext currentCx = cx != null ? cx : JSContext.current();
		JSPromise outPromise = new JSPromise(currentCx);
		List<Object> items = toList(iterable);
		if (items == null) {
			outPromise.reject(currentCx != null ? JSContext.makeTypeError("Promise.any requires an iterable") : new IllegalArgumentException("Not iterable"));
			return outPromise;
		}
		int count = items.size();
		if (count == 0) {
			JSObject aggErr = new JSObject();
			aggErr.put("name", "AggregateError");
			aggErr.put("message", "All promises were rejected");
			aggErr.put("errors", new JSArray());
			outPromise.reject(aggErr);
			return outPromise;
		}

		JSArray errors = new JSArray();
		errors.setLength(count);
		for (int i = 0; i < count; i++) errors.setElement(i, JSUndefined.INSTANCE);
		AtomicInteger remaining = new AtomicInteger(count);
		AtomicBoolean done = new AtomicBoolean(false);

		for (int i = 0; i < count; i++) {
			final int index = i;
			Object item = items.get(i);
			resolve(currentCx, item).then(currentCx,
				val -> {
					if (done.compareAndSet(false, true)) {
						outPromise.fulfill(val);
					}
					return JSUndefined.INSTANCE;
				},
				err -> {
					errors.setElement(index, err);
					if (remaining.decrementAndGet() == 0 && done.compareAndSet(false, true)) {
						JSObject aggErr = new JSObject();
						aggErr.put("name", "AggregateError");
						aggErr.put("message", "All promises were rejected");
						aggErr.put("errors", errors);
						outPromise.reject(aggErr);
					}
					return JSUndefined.INSTANCE;
				}
			);
		}

		return outPromise;
	}

	private static List<Object> toList(Object iterable) {
		if (iterable instanceof JSArray arr) {
			List<Object> list = new ArrayList<>();
			long len = arr.length();
			for (int i = 0; i < len; i++) {
				list.add(arr.getElement(i));
			}
			return list;
		}
		if (iterable instanceof List<?> list) {
			return new ArrayList<>(list);
		}
		if (iterable instanceof Object[] arr) {
			return Arrays.asList(arr);
		}
		return null;
	}

	/**
	 * 挂起当前线程等待 Promise 敲定（await 语义实现）。
	 */
	public static Object await(JSContext cx, Object val) throws Throwable {
		JSContext currentCx = cx != null ? cx : JSContext.current();
		AsyncExecutionState state = AsyncExecutionState.CURRENT.get();
		if (state != null) {
			state.onFirstAwait();
		}

		JSPromise promise = resolve(currentCx, val);
		CompletableFuture<Object> resumeFuture = new CompletableFuture<>();

		Runnable onComplete = () -> {
			if (promise.state == FULFILLED) {
				resumeFuture.complete(promise.result);
			} else {
				Object reason = promise.result;
				Throwable t = reason instanceof Throwable th ? th : new JSOps.JSException(reason);
				resumeFuture.completeExceptionally(t);
			}
		};

		promise.whenSettled(currentCx, onComplete);

		while (!resumeFuture.isDone()) {
			if (currentCx != null) {
				currentCx.drainMicrotasks();
			}
			if (resumeFuture.isDone()) break;
			try {
				return resumeFuture.get(1, TimeUnit.MILLISECONDS);
			} catch (TimeoutException ignored) {
			} catch (ExecutionException ee) {
				Throwable cause = ee.getCause();
				throw cause != null ? cause : ee;
			}
		}
		try {
			return resumeFuture.get();
		} catch (ExecutionException ee) {
			Throwable cause = ee.getCause();
			throw cause != null ? cause : ee;
		}
	}
}
