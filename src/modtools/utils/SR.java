package modtools.utils;

import arc.func.*;

import java.util.function.*;

import static modtools.utils.Tools.as;

/** @author I-hope1  */
public class SR<T> {
	private static final ThreadLocal<SR<?>> local  = ThreadLocal.withInitial(SR::new);
	public static <R> SR<R> of(R value) {
		SR<R> instance = as(local.get());
		return instance.setv(value);
	}

	private T value;
	// 私有构造器
	private SR() { }

	public static void apply(Runnable run) {
		try {
			run.run();
		} catch (SatisfyException ignored) { }
	}

	public SR<T> setv(T value) {
		this.value = value;
		return this;
	}
	public SR<T> setOpt(Function<T, T> func) {
		if (func != null) value = func.apply(value);
		return this;
	}
	public SR<T> setnull(Predicate<T> condition) {
		return set(condition, (T) null);
	}

	public SR<T> set(Predicate<T> conditon, T newValue) {
		if (conditon != null && conditon.test(value)) value = newValue;
		return this;
	}
	public SR<T> set(Predicate<T> conditon, Supplier<T> newValue) {
		if (conditon != null && conditon.test(value)) value = newValue.get();
		return this;
	}
	public <R> SR<R> reset(Function<T, R> func) {
		if (func == null) throw new NullPointerException("func is null");
		SR<R> self = as(this);
		self.value = func.apply(value);
		return self;
	}

	/**
	 * {@link SR#value}是否存在（不为{@code null}）
	 * 存在就执行代码
	 */
	public SR<T> ifPresent(Consumer<T> cons) {
		return ifPresent(cons, null);
	}

	public SR<T> ifPresent(Consumer<T> cons, Runnable nullrun) {
		if (value != null) cons.accept(value);
		else if (nullrun != null) nullrun.run();
		return this;
	}

	/**
	 * @param cons 如果满足就执行
	 * @throws RuntimeException 当执行后抛出
	 */
	public <R> SR<T> isInstance(Class<R> cls, Consumer<R> cons) throws SatisfyException {
		if (cls.isInstance(value)) {
			cons.accept(cls.cast(value));
			throw new SatisfyException();
		}
		return this;
	}
	public <R, A> SR<T> isInstance(Class<R> cls, A arg1, BiConsumer<R, A> cons) throws SatisfyException {
		if (cls.isInstance(value)) {
			cons.accept(cls.cast(value), arg1);
			throw new SatisfyException();
		}
		return this;
	}

	public SR<T> cons(boolean b, TBoolc<T> cons) {
		cons.get(value, b);
		return this;
	}
	public SR<T> cons(float f, TFloatc<T> cons) {
		cons.get(value, f);
		return this;
	}
	public SR<T> cons(int i, TIntc<T> cons) {
		cons.get(value, i);
		return this;
	}
	public <R> SR<T> cons(R obj, BiConsumer<T, R> cons) {
		cons.accept(value, obj);
		return this;
	}

	public SR<T> cons(Consumer<T> cons) {
		cons.accept(value);
		return this;
	}
	public Runnable asRun(Consumer<T> cons) {
		T value = this.value;
		return () -> cons.accept(value);
	}
	/** 绑定到单元格值 */
	public <U> Cons<U> bindTo(Cons2<U, T> cons) {
		T value = this.value;
		return v -> cons.get(v, value);
	}
	public SR<T> cons(Predicate<T> boolf, Consumer<T> cons) {
		if (boolf.test(value)) cons.accept(value);
		return this;
	}
	public SR<T> consNot(Predicate<T> boolf, Consumer<T> cons) {
		if (!boolf.test(value)) cons.accept(value);
		return this;
	}


	public SR<T> ifRun(boolean b, Consumer<T> cons) {
		if (b) cons.accept(value);
		return this;
	}

	/** @return {@code true} if valid. */
	public boolean test(Predicate<T> predicate) {
		return value != null && predicate.test(value);
	}
	public T get() {
		return value;
	}
	public <R> R get(Function<T, R> func) {
		return func.apply(value);
	}

	/**
	 * 判断是否继承
	 * @param cons    形参为满足的{@code class}
	 * @param classes 判断的类
	 */
	public SR<T> isExtend(Consumer<Class<?>> cons, Class<?>... classes) {
		if (!(value instanceof Class<?> origin)) throw new IllegalStateException("Value isn't a class");

		for (Class<?> cl : classes) {
			if (cl.isAssignableFrom(origin)) {
				cons.accept(cl);
				throw new SatisfyException();
			}
		}
		return this;
	}

	public static class SatisfyException extends RuntimeException { }
	public interface TBoolc<T> {
		void get(T p1, boolean p2);
	}
	public interface TFloatc<T> {
		void get(T p1, float p2);
	}
	public interface TIntc<T> {
		void get(T p1, int p2);
	}

	public static <V, P2> Cons<V> makeCons(P2 p2, Cons2<V, P2> cons2) {
		return v -> cons2.get(v, p2);
	}
	public static <P1, V> Cons<V> makeCons1(P1 p1, Cons2<P1, V> cons2) {
		return v -> cons2.get(p1, v);
	}

}
