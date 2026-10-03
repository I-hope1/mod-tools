package nipx.ref;

import arc.*;
import arc.func.*;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.TextField.TextFieldValidator;
import arc.scene.ui.layout.Cell;
import arc.scene.utils.Disableable;
import arc.struct.*;
import nipx.HotSwapAgent;

import java.util.Objects;

/**
 * 热重载（HotSwap）中 Lambda 表达式与回调函数式接口的容错及精准熔断代理包装器。
 * <p>
 * <b>背景与设计初衷：</b><br>
 * 在运行时进行字节码热替换时，若修改或删除了某些方法/签名，旧存活的 UI 或后台 Lambda 引用在下次触发时
 * 可能会抛出 {@link LinkageError}（例如 {@link NoSuchMethodError}）。若未经拦截，高频调用的定时任务（如每帧 {@code update}）
 * 将陷入每秒 60 次的崩溃刷屏循环；而在以往粗暴调用 {@code element.remove()} 的设计下，又会导致整个组件被意外删除。
 * <p>
 * <b>核心工作机制：</b>
 * <ul>
 *   <li><b>动态代理：</b>在方法调用入口处代理原始函数式接口，透明捕获所有 {@link LinkageError}。</li>
 *   <li><b>精准局部熔断：</b>通过 {@link #onRemove} 提供细粒度的资源注销与置空（例如 {@code el.update(null)}、{@code el.removeListener(ref)}），
 *       确保故障仅在局部隔离，绝不波及宿主组件的正常渲染与展示。</li>
 *   <li><b>Events 事件总线熔断：</b>底层重写 {@link arc.Events#on} / {@link arc.Events#run}，通过原生字节码自举直传私有注册表；
 *       监听器失效时通过注册表将失效监听器即刻注销，阻断 60FPS 高频事件（如 {@code Trigger.update}）死循环与泄漏，且异步解绑保护内部遍历安全。</li>
 *   <li><b>细粒度闭包释放：</b>针对局部熔断捕获的 UI 节点与回调闭包，在触发熔断时通过哨兵对象 {@link #REMOVED} 瞬时切断引用，对 GC 极度友好。</li>
 *   <li><b>静默降级（Silent）：</b>对于点击、鼠标悬停、弹窗生命周期等瞬时事件，采用 {@link #wrapSilent}，异常时仅将内部引用置空静音。</li>
 *   <li><b>与 {@link nipx.LambdaAligner} 的双轨协同：</b><br>
 *       对于已被删除的“孤儿方法”，{@code LambdaAligner} 内部通过 {@link StackWalker} 探测调用栈：
 *       若检测到当前由 {@link UpdateRef} 调用，则定向抛出 {@link NoSuchMethodError}，精准触发此处的局部熔断与清理动作；
 *       若为普通业务代码调用，则静默返回类型默认值，绝不中断业务。</li>
 * </ul>
 * @see nipx.LambdaAligner
 * @see nipx.LambdaRef
 * @see nipx.Injector
 */
public class UpdateRef {

	/** 空操作常量，用于事件回调发生异常时的静默熔断，防止触发任何外部破坏性清理 */
	public static final Runnable NOOP = () -> { };

	/** 标记熔断清理已被触发的哨兵对象，替代原有的 boolean removed 标志，兼顾状态判定与闭包引用释放 */
	public static final Runnable REMOVED = () -> {
		throw new UnsupportedOperationException();
	};

	/** 原始函数式接口实例，不随熔断置空，供比较、哈希与透传提取 */
	private final    Object   original;
	/** 当前被代理的目标函数式接口实例（例如 {@link Runnable}、{@link Cons} 等）；发生异常或注销后置为 null */
	private volatile Object   fn;
	/** 自定义熔断/销毁动作；为 null 时表示静默失效；为 {@link #REMOVED} 时表示已触发熔断清理 */
	private volatile Runnable onRemove;

	/** 线程本地上下文，支持通过 {@link #withOnRemove} 跨调用栈隐式传递熔断清理回调 */
	private static final ThreadLocal<Runnable> CONTEXT_ON_REMOVE = new ThreadLocal<>();

	/**
	 * 合并两个清理动作。按序执行，任何一方抛出异常均被隔离捕获并记录日志，不影响后续清理。
	 * @param a 首要清理动作
	 * @param b 次要/上下文清理动作
	 * @return 合并后的单一动作
	 */
	public static Runnable combine(Runnable a, Runnable b) {
		if (a == null || a == NOOP || a == REMOVED) return b;
		if (b == null || b == NOOP || b == REMOVED) return a;
		return () -> {
			try {
				a.run();
			} catch (Throwable t) {
				HotSwapAgent.error("[UpdateRef] onRemove failed: " + t.getMessage(), t);
			}
			try {
				b.run();
			} catch (Throwable t) {
				HotSwapAgent.error("[UpdateRef] context onRemove failed: " + t.getMessage(), t);
			}
		};
	}

	/**
	 * 初始化包装引用与熔断动作，自动与当前线程上下文清理动作（{@link #CONTEXT_ON_REMOVE}）合并。
	 * @param original 原始函数式接口实例
	 * @param onRemove 显式指定的清理动作
	 */
	private UpdateRef(Object original, Runnable onRemove) {
		this.original = original;
		this.fn = original;
		this.onRemove = combine(onRemove, CONTEXT_ON_REMOVE.get());
	}

	/**
	 * 获取被代理的原始函数式接口对象。
	 */
	public Object getOriginal() {
		return original;
	}

	/**
	 * 获取当前配置的熔断清理动作。
	 * @return 当前绑定的清理动作，若已熔断或未设置则返回 null
	 */
	public Runnable getOnRemove() {
		Runnable r = onRemove;
		return r == REMOVED ? null : r;
	}

	/**
	 * 动态设置或替换当前包装引用的熔断清理动作。
	 * 若当前已处于熔断状态（{@code onRemove == REMOVED}），则忽略此设置。
	 * @param onRemove 新的清理动作
	 */
	public void setOnRemove(Runnable onRemove) {
		synchronized (this) {
			if (this.onRemove != REMOVED) {
				this.onRemove = onRemove;
			}
		}
	}

	/**
	 * 为当前包装引用追加合并新的清理动作。
	 * 若当前已处于熔断状态（{@code onRemove == REMOVED}），则忽略此操作。
	 * @param action 待追加的清理动作
	 */
	public void addOnRemove(Runnable action) {
		if (action == null || action == NOOP || action == REMOVED) return;
		synchronized (this) {
			if (this.onRemove != REMOVED) {
				this.onRemove = combine(this.onRemove, action);
			}
		}
	}

	/**
	 * 在指定代码块的作用域内设置默认的 {@code onRemove} 动作。
	 * 该作用域内由 {@link UpdateRef} 创建且未显式指定清理动作的包装实例，将自动继承此动作。
	 * @param onRemoveAction 该作用域内默认的清理动作
	 * @param block          受该作用域保护并执行的代码块
	 */
	public static void withOnRemove(Runnable onRemoveAction, Runnable block) {
		Runnable old = CONTEXT_ON_REMOVE.get();
		CONTEXT_ON_REMOVE.set(combine(old, onRemoveAction));
		try {
			block.run();
		} finally {
			if (old == null) {
				CONTEXT_ON_REMOVE.remove();
			} else {
				CONTEXT_ON_REMOVE.set(old);
			}
		}
	}

	/**
	 * 在指定代码块的作用域内设置默认的 {@code onRemove} 动作并返回执行结果。
	 * @param onRemoveAction 该作用域内默认的清理动作
	 * @param block          受该作用域保护并提供返回值的代码块
	 * @param <T>            返回值类型
	 * @return 代码块的执行返回值
	 */
	public static <T> T withOnRemove(Runnable onRemoveAction, Prov<T> block) {
		Runnable old = CONTEXT_ON_REMOVE.get();
		CONTEXT_ON_REMOVE.set(combine(old, onRemoveAction));
		try {
			return block.get();
		} finally {
			if (old == null) {
				CONTEXT_ON_REMOVE.remove();
			} else {
				CONTEXT_ON_REMOVE.set(old);
			}
		}
	}

	//region 包装器接口与具名实现

	/**
	 * 标识已受 {@link UpdateRef} 保护的代理对象，支持取回底层引用以追加清理动作或访问原始委托实例。
	 */
	public interface WrappedRef {
		UpdateRef getUpdateRef();

		default Object getOriginal() {
			UpdateRef ref = getUpdateRef();
			return ref != null ? ref.getOriginal() : null;
		}
	}

	public static class WrappedRunnable implements Runnable, WrappedRef {
		private final UpdateRef ref;

		public WrappedRunnable(UpdateRef ref) {
			this.ref = ref;
		}

		@Override
		public void run() {
			ref.run();
		}

		@Override
		public UpdateRef getUpdateRef() {
			return ref;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null) return false;
			Object orig = getOriginal();
			if (o == orig) return true;
			if (o instanceof WrappedRef wr) return Objects.equals(orig, wr.getOriginal());
			return Objects.equals(orig, o);
		}

		@Override
		public int hashCode() {
			Object orig = getOriginal();
			return orig != null ? orig.hashCode() : 0;
		}
	}

	public static class WrappedProv<T> implements Prov<T>, WrappedRef {
		private final UpdateRef ref;

		public WrappedProv(UpdateRef ref) {
			this.ref = ref;
		}

		@Override
		public T get() {
			return ref.runProv();
		}

		@Override
		public UpdateRef getUpdateRef() {
			return ref;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null) return false;
			Object orig = getOriginal();
			if (o == orig) return true;
			if (o instanceof WrappedRef wr) return Objects.equals(orig, wr.getOriginal());
			return Objects.equals(orig, o);
		}

		@Override
		public int hashCode() {
			Object orig = getOriginal();
			return orig != null ? orig.hashCode() : 0;
		}
	}

	public static class WrappedBoolp implements Boolp, WrappedRef {
		private final UpdateRef ref;
		private final Boolp     fallback;

		public WrappedBoolp(UpdateRef ref) {
			this(ref, null);
		}

		public WrappedBoolp(UpdateRef ref, Boolp fallback) {
			this.ref = ref;
			this.fallback = fallback;
		}

		@Override
		public boolean get() {
			return ref.runBoolp(fallback);
		}

		@Override
		public UpdateRef getUpdateRef() {
			return ref;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null) return false;
			Object orig = getOriginal();
			if (o == orig) return true;
			if (o instanceof WrappedRef wr) return Objects.equals(orig, wr.getOriginal());
			return Objects.equals(orig, o);
		}

		@Override
		public int hashCode() {
			Object orig = getOriginal();
			return orig != null ? orig.hashCode() : 0;
		}
	}

	public static class WrappedCons<T> implements Cons<T>, WrappedRef {
		private final UpdateRef ref;

		public WrappedCons(UpdateRef ref) {
			this.ref = ref;
		}

		@Override
		public void get(T t) {
			ref.runCons(t);
		}

		@Override
		public UpdateRef getUpdateRef() {
			return ref;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null) return false;
			Object orig = getOriginal();
			if (o == orig) return true;
			if (o instanceof WrappedRef wr) return Objects.equals(orig, wr.getOriginal());
			return Objects.equals(orig, o);
		}

		@Override
		public int hashCode() {
			Object orig = getOriginal();
			return orig != null ? orig.hashCode() : 0;
		}
	}

	public static class WrappedBoolf<T> implements Boolf<T>, WrappedRef {
		private final UpdateRef ref;
		private final Boolf<T>  fallback;

		public WrappedBoolf(UpdateRef ref) {
			this(ref, null);
		}

		public WrappedBoolf(UpdateRef ref, Boolf<T> fallback) {
			this.ref = ref;
			this.fallback = fallback;
		}

		@Override
		public boolean get(T t) {
			return ref.runBoolf(t, fallback);
		}

		@Override
		public UpdateRef getUpdateRef() {
			return ref;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null) return false;
			Object orig = getOriginal();
			if (o == orig) return true;
			if (o instanceof WrappedRef wr) return Objects.equals(orig, wr.getOriginal());
			return Objects.equals(orig, o);
		}

		@Override
		public int hashCode() {
			Object orig = getOriginal();
			return orig != null ? orig.hashCode() : 0;
		}
	}

	public static class WrappedValidator implements TextFieldValidator, WrappedRef {
		private final UpdateRef          ref;
		private final TextFieldValidator fallback;

		public WrappedValidator(UpdateRef ref) {
			this(ref, null);
		}

		public WrappedValidator(UpdateRef ref, TextFieldValidator fallback) {
			this.ref = ref;
			this.fallback = fallback;
		}

		@Override
		public boolean valid(String text) {
			return ref.runValidator(text, fallback);
		}

		@Override
		public UpdateRef getUpdateRef() {
			return ref;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null) return false;
			Object orig = getOriginal();
			if (o == orig) return true;
			if (o instanceof WrappedRef wr) return Objects.equals(orig, wr.getOriginal());
			return Objects.equals(orig, o);
		}

		@Override
		public int hashCode() {
			Object orig = getOriginal();
			return orig != null ? orig.hashCode() : 0;
		}
	}

	public static class WrappedFloatc implements Floatc, WrappedRef {
		private final UpdateRef ref;

		public WrappedFloatc(UpdateRef ref) {
			this.ref = ref;
		}

		@Override
		public void get(float f) {
			ref.runFloatc(f);
		}

		@Override
		public UpdateRef getUpdateRef() {
			return ref;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null) return false;
			Object orig = getOriginal();
			if (o == orig) return true;
			if (o instanceof WrappedRef wr) return Objects.equals(orig, wr.getOriginal());
			return Objects.equals(orig, o);
		}

		@Override
		public int hashCode() {
			Object orig = getOriginal();
			return orig != null ? orig.hashCode() : 0;
		}
	}

	public static class WrappedFloatc2 implements Floatc2, WrappedRef {
		private final UpdateRef ref;

		public WrappedFloatc2(UpdateRef ref) {
			this.ref = ref;
		}

		@Override
		public void get(float f1, float f2) {
			ref.runFloatc2(f1, f2);
		}

		@Override
		public UpdateRef getUpdateRef() {
			return ref;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null) return false;
			Object orig = getOriginal();
			if (o == orig) return true;
			if (o instanceof WrappedRef wr) return Objects.equals(orig, wr.getOriginal());
			return Objects.equals(orig, o);
		}

		@Override
		public int hashCode() {
			Object orig = getOriginal();
			return orig != null ? orig.hashCode() : 0;
		}
	}

	public static class WrappedEventListener implements EventListener, WrappedRef {
		private final UpdateRef ref;

		public WrappedEventListener(UpdateRef ref) {
			this.ref = ref;
		}

		@Override
		public boolean handle(SceneEvent event) {
			return ref.runEventListener(event);
		}

		@Override
		public UpdateRef getUpdateRef() {
			return ref;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null) return false;
			Object orig = getOriginal();
			if (o == orig) return true;
			if (o instanceof WrappedRef wr) return Objects.equals(orig, wr.getOriginal());
			return Objects.equals(orig, o);
		}

		@Override
		public int hashCode() {
			Object orig = getOriginal();
			return orig != null ? orig.hashCode() : 0;
		}
	}

	//endregion

	//region 通用 wrap 重载（不依赖 Element）

	/**
	 * 将原始 {@link Runnable} 包装为具备热重载容错保护的代理，异常时静默失效。
	 * @param original 原始 Runnable 实例
	 * @return 具备容错保护的代理 Runnable
	 */
	public static Runnable wrap(Runnable original) {
		return wrap(original, null);
	}

	/**
	 * 将原始 {@link Runnable} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 * @param original 原始 Runnable 实例
	 * @param onRemove 发生 LinkageError 时的清理/注销动作
	 * @return 具备容错保护的代理 Runnable
	 */
	public static Runnable wrap(Runnable original, Runnable onRemove) {
		if (original == null) return null;
		if (original instanceof WrappedRef wr) {
			if (onRemove != null) wr.getUpdateRef().addOnRemove(onRemove);
			return original;
		}
		return new WrappedRunnable(new UpdateRef(original, onRemove));
	}

	/**
	 * 将原始 {@link Prov} 包装为具备热重载容错保护的代理，异常时返回 null 并静默失效。
	 * @param original 原始 Prov 实例
	 * @param <T>      提供的值类型
	 * @return 具备容错保护的代理 Prov
	 */
	public static <T> Prov<T> wrap(Prov<T> original) {
		return wrap(original, null);
	}

	/**
	 * 将原始 {@link Prov} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 * @param original 原始 Prov 实例
	 * @param onRemove 发生 LinkageError 时的清理/注销动作
	 * @param <T>      提供的值类型
	 * @return 具备容错保护的代理 Prov
	 */
	public static <T> Prov<T> wrap(Prov<T> original, Runnable onRemove) {
		if (original == null) return null;
		if (original instanceof WrappedRef wr) {
			if (onRemove != null) wr.getUpdateRef().addOnRemove(onRemove);
			return original;
		}
		return new WrappedProv<>(new UpdateRef(original, onRemove));
	}

	/**
	 * 将原始 {@link Boolp} 包装为具备热重载容错保护的代理，异常时返回 false 并静默失效。
	 * @param original 原始 Boolp 实例
	 * @return 具备容错保护的代理 Boolp
	 */
	public static Boolp wrap(Boolp original) {
		return wrap(original, null);
	}

	/**
	 * 将原始 {@link Boolp} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 * @param original 原始 Boolp 实例
	 * @param onRemove 发生 LinkageError 时的清理/注销动作
	 * @return 具备容错保护的代理 Boolp
	 */
	public static Boolp wrap(Boolp original, Runnable onRemove) {
		if (original == null) return null;
		if (original instanceof WrappedRef wr) {
			if (onRemove != null) wr.getUpdateRef().addOnRemove(onRemove);
			return original;
		}
		return new WrappedBoolp(new UpdateRef(original, onRemove));
	}

	/**
	 * 将原始 {@link Cons} 包装为具备热重载容错保护的代理，异常时静默失效。
	 * @param original 原始 Cons 实例
	 * @param <T>      消费的值类型
	 * @return 具备容错保护的代理 Cons
	 */
	public static <T> Cons<T> wrap(Cons<T> original) {
		return wrap(original, null);
	}

	/**
	 * 将原始 {@link Cons} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 * @param original 原始 Cons 实例
	 * @param onRemove 发生 LinkageError 时的清理/注销动作
	 * @param <T>      消费的值类型
	 * @return 具备容错保护的代理 Cons
	 */
	public static <T> Cons<T> wrap(Cons<T> original, Runnable onRemove) {
		if (original == null) return null;
		if (original instanceof WrappedRef wr) {
			if (onRemove != null) wr.getUpdateRef().addOnRemove(onRemove);
			return original;
		}
		return new WrappedCons<>(new UpdateRef(original, onRemove));
	}
	//endregion

	//region 兼容 Element 的通用 wrap 重载

	/**
	 * 将原始 {@link Runnable} 包装为具备热重载容错保护的代理（兼容原有 Element 接口）。
	 * @param element  宿主 Element（仅作兼容参数，UpdateRef 不再强持有该引用）
	 * @param original 原始 Runnable 实例
	 * @return 具备容错保护的代理 Runnable
	 */
	public static Runnable wrap(Element element, Runnable original) {
		return wrap(element, original, null);
	}

	/**
	 * 将原始 {@link Runnable} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 * @param element  宿主 Element
	 * @param original 原始 Runnable 实例
	 * @param onRemove 发生 LinkageError 时的清理/注销动作
	 * @return 具备容错保护的代理 Runnable
	 */
	public static Runnable wrap(Element element, Runnable original, Runnable onRemove) {
		return wrap(original, onRemove);
	}

	/**
	 * 将原始 {@link Prov} 包装为具备热重载容错保护的代理（兼容原有 Element 接口）。
	 * @param element  宿主 Element
	 * @param original 原始 Prov 实例
	 * @return 具备容错保护的代理 Prov
	 */
	public static Prov<?> wrap(Element element, Prov<?> original) {
		return wrap(element, original, null);
	}

	/**
	 * 将原始 {@link Prov} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 * @param element  宿主 Element
	 * @param original 原始 Prov 实例
	 * @param onRemove 发生 LinkageError 时的清理/注销动作
	 * @return 具备容错保护的代理 Prov
	 */
	public static Prov<?> wrap(Element element, Prov<?> original, Runnable onRemove) {
		return wrap(original, onRemove);
	}

	/**
	 * 将原始 {@link Boolp} 包装为具备热重载容错保护的代理（兼容原有 Element 接口）。
	 * @param element  宿主 Element
	 * @param original 原始 Boolp 实例
	 * @return 具备容错保护的代理 Boolp
	 */
	public static Boolp wrap(Element element, Boolp original) {
		return wrap(element, original, null);
	}

	/**
	 * 将原始 {@link Boolp} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 * @param element  宿主 Element
	 * @param original 原始 Boolp 实例
	 * @param onRemove 发生 LinkageError 时的清理/注销动作
	 * @return 具备容错保护的代理 Boolp
	 */
	public static Boolp wrap(Element element, Boolp original, Runnable onRemove) {
		return wrap(original, onRemove);
	}

	/**
	 * 将原始 {@link Cons} 包装为具备热重载容错保护的代理（兼容原有 Element 接口）。
	 * @param element  宿主 Element
	 * @param original 原始 Cons 实例
	 * @return 具备容错保护的代理 Cons
	 */
	public static Cons<?> wrap(Element element, Cons<?> original) {
		return wrap(element, original, null);
	}

	/**
	 * 将原始 {@link Cons} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 * @param element  宿主 Element
	 * @param original 原始 Cons 实例
	 * @param onRemove 发生 LinkageError 时的清理/注销动作
	 * @return 具备容错保护的代理 Cons
	 */
	public static Cons<?> wrap(Element element, Cons<?> original, Runnable onRemove) {
		return wrap(original, onRemove);
	}

	/**
	 * 将原始 {@link Boolf} 包装为具备热重载容错保护的代理（兼容原有 Element 接口）。
	 * @param element  宿主 Element
	 * @param original 原始 Boolf 实例
	 * @return 具备容错保护的代理 Boolf
	 */
	public static Boolf<?> wrap(Element element, Boolf<?> original) {
		return wrap(element, original, null);
	}

	/**
	 * 将原始 {@link Boolf} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 * @param element  宿主 Element
	 * @param original 原始 Boolf 实例
	 * @param onRemove 发生 LinkageError 时的清理/注销动作
	 * @return 具备容错保护的代理 Boolf
	 */
	public static Boolf<?> wrap(Element element, Boolf<?> original, Runnable onRemove) {
		if (original == null) return null;
		if (original instanceof WrappedRef wr) {
			if (onRemove != null) wr.getUpdateRef().addOnRemove(onRemove);
			return original;
		}
		return new WrappedBoolf<>(new UpdateRef(original, onRemove));
	}

	/**
	 * 将文本框验证器 {@link TextFieldValidator} 包装为具备热重载容错保护的代理。
	 * @param element  宿主 Element
	 * @param original 原始验证器
	 * @return 具备容错保护的代理验证器
	 */
	public static TextFieldValidator wrap(Element element, TextFieldValidator original) {
		return wrap(element, original, null);
	}

	/**
	 * 将文本框验证器 {@link TextFieldValidator} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 * @param element  宿主 Element
	 * @param original 原始验证器
	 * @param onRemove 发生 LinkageError 时的清理动作
	 * @return 具备容错保护的代理验证器
	 */
	public static TextFieldValidator wrap(Element element, TextFieldValidator original, Runnable onRemove) {
		if (original == null) return null;
		if (original instanceof WrappedRef wr) {
			if (onRemove != null) wr.getUpdateRef().addOnRemove(onRemove);
			return original;
		}
		return new WrappedValidator(new UpdateRef(original, onRemove));
	}

	/**
	 * 将原始单浮点消费回调 {@link Floatc} 包装为具备热重载容错保护的代理。
	 * @param element  宿主 Element
	 * @param original 原始 Floatc 实例
	 * @return 具备容错保护的代理 Floatc
	 */
	public static Floatc wrap(Element element, Floatc original) {
		return wrap(element, original, null);
	}

	/**
	 * 将原始单浮点消费回调 {@link Floatc} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 * @param element  宿主 Element
	 * @param original 原始 Floatc 实例
	 * @param onRemove 发生 LinkageError 时的清理动作
	 * @return 具备容错保护的代理 Floatc
	 */
	public static Floatc wrap(Element element, Floatc original, Runnable onRemove) {
		if (original == null) return null;
		if (original instanceof WrappedRef wr) {
			if (onRemove != null) wr.getUpdateRef().addOnRemove(onRemove);
			return original;
		}
		return new WrappedFloatc(new UpdateRef(original, onRemove));
	}

	/**
	 * 将原始双浮点消费回调 {@link Floatc2} 包装为具备热重载容错保护的代理。
	 * @param element  宿主 Element
	 * @param original 原始 Floatc2 实例
	 * @return 具备容错保护的代理 Floatc2
	 */
	public static Floatc2 wrap(Element element, Floatc2 original) {
		return wrap(element, original, null);
	}

	/**
	 * 将原始双浮点消费回调 {@link Floatc2} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 * @param element  宿主 Element
	 * @param original 原始 Floatc2 实例
	 * @param onRemove 发生 LinkageError 时的清理动作
	 * @return 具备容错保护的代理 Floatc2
	 */
	public static Floatc2 wrap(Element element, Floatc2 original, Runnable onRemove) {
		if (original == null) return null;
		if (original instanceof WrappedRef wr) {
			if (onRemove != null) wr.getUpdateRef().addOnRemove(onRemove);
			return original;
		}
		return new WrappedFloatc2(new UpdateRef(original, onRemove));
	}

	/**
	 * 将事件监听器 {@link EventListener} 包装为具备热重载容错保护的代理。
	 * 默认在发生异常时精准注销该监听器（{@code element.removeListener}）。
	 * @param element  宿主 Element
	 * @param original 原始监听器
	 * @return 具备容错保护的代理监听器
	 */
	public static EventListener wrap(Element element, EventListener original) {
		return wrap(element, original, null);
	}

	/**
	 * 将事件监听器 {@link EventListener} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 * @param element  宿主 Element
	 * @param original 原始监听器
	 * @param onRemove 自定义熔断动作；若为 null，默认精准注销该监听器
	 * @return 具备容错保护的代理监听器
	 */
	public static EventListener wrap(Element element, EventListener original, Runnable onRemove) {
		if (original == null) return null;
		if (original instanceof WrappedRef wr) {
			if (element != null) {
				wr.getUpdateRef().addOnRemove(() -> element.removeListener(original));
			}
			if (onRemove != null) wr.getUpdateRef().addOnRemove(onRemove);
			return original;
		}
		EventListener[] box = new EventListener[1];
		Runnable defaultRemove = () -> {
			if (element != null && box[0] != null) element.removeListener(box[0]);
		};
		Runnable combined = combine(defaultRemove, onRemove);
		UpdateRef            ref      = new UpdateRef(original, combined);
		WrappedEventListener listener = new WrappedEventListener(ref);
		box[0] = listener;
		return listener;
	}

	//endregion

	//region 场景专用 wrap 方法（精准局部熔断）

	/**
	 * 包装 {@link Element#update(Runnable)} 回调。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：{@code element.update(null)}，仅移除该每帧更新回调，绝不删除 Element。
	 * @param element  目标 UI 节点
	 * @param original 原始更新回调
	 * @return 具备局部熔断保护的代理 Runnable
	 */
	public static Runnable wrapUpdate(Element element, Runnable original) {
		if (original == null) return null;
		Runnable removeAction = () -> {
			if (element != null) element.update(null);
		};
		if (original instanceof WrappedRef wr) {
			wr.getUpdateRef().addOnRemove(removeAction);
			return original;
		}
		return new WrappedRunnable(new UpdateRef(original, removeAction));
	}

	/**
	 * 包装 {@link Element#visible(Boolp)} 条件回调。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：恢复可见并注销条件，绝不让元素永久消失。
	 * @param element  目标 UI 节点
	 * @param original 原始可见性提供器
	 * @return 具备局部熔断保护的代理 Boolp
	 */
	public static Boolp wrapVisible(Element element, Boolp original) {
		if (original == null) return null;
		Runnable removeAction = () -> {
			if (element != null) {
				element.visible(null);
				element.visible = true;
			}
		};
		if (original instanceof WrappedRef wr) {
			wr.getUpdateRef().addOnRemove(removeAction);
			return original;
		}
		UpdateRef ref = new UpdateRef(original, removeAction);
		return new WrappedBoolp(ref, () -> element == null || element.visible);
	}

	/**
	 * 包装 {@link Element#touchable(Prov)} 条件回调。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：{@code element.touchable((Prov) null)}，仅移除可触摸状态动态提供器。
	 * @param element  目标 UI 节点
	 * @param original 原始 Touchable 提供器
	 * @return 具备局部熔断保护的代理 Prov
	 */
	public static Prov<?> wrapTouchable(Element element, Prov<?> original) {
		if (original == null) return null;
		Runnable removeAction = () -> {
			if (element != null) element.touchable(null);
		};
		if (original instanceof WrappedRef wr) {
			wr.getUpdateRef().addOnRemove(removeAction);
			return original;
		}
		return new WrappedProv<>(new UpdateRef(original, removeAction));
	}

	/**
	 * 包装 {@link Button#setDisabled(Boolp)} 禁用条件回调。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：{@code button.setDisabled(null)}，仅移除禁用条件。
	 * @param element  目标按钮元素
	 * @param original 原始禁用状态提供器
	 * @return 具备局部熔断保护的代理 Boolp
	 */
	public static Boolp wrapButtonDisabled(Button element, Boolp original) {
		if (original == null) return null;
		Runnable removeAction = () -> {
			if (element instanceof Button b) b.setDisabled(null);
		};
		if (original instanceof WrappedRef wr) {
			wr.getUpdateRef().addOnRemove(removeAction);
			return original;
		}
		return new WrappedBoolp(new UpdateRef(original, removeAction), element::isDisabled);
	}

	/**
	 * 包装 {@link TextField#setValidator(TextFieldValidator)} 输入验证器。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：{@code textField.setValidator(null)}，仅移除验证规则。
	 * @param element  目标输入框元素
	 * @param original 原始输入验证器
	 * @return 具备局部熔断保护的代理 TextFieldValidator
	 */
	public static TextFieldValidator wrapValidator(TextField element, TextFieldValidator original) {
		if (original == null) return null;
		Runnable removeAction = () -> {
			if (element instanceof TextField tf) tf.setValidator(null);
		};
		if (original instanceof WrappedRef wr) {
			wr.getUpdateRef().addOnRemove(removeAction);
			return original;
		}
		return new WrappedValidator(new UpdateRef(original, removeAction), text -> element.isValid());
	}

	/**
	 * 包装 {@link Element#addListener(EventListener)} 事件监听器。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：{@code element.removeListener(ref)}，仅注销当前出故障的监听器。
	 * @param element  目标 UI 节点
	 * @param original 原始监听器
	 * @return 具备局部熔断保护的代理 EventListener
	 */
	public static EventListener wrapListener(Element element, EventListener original) {
		if (original == null) return null;
		if (original instanceof WrappedRef wr) {
			if (element != null) {
				wr.getUpdateRef().addOnRemove(() -> element.removeListener(original));
			}
			return original;
		}
		EventListener[] box = new EventListener[1];
		Runnable removeAction = () -> {
			if (element != null && box[0] != null) element.removeListener(box[0]);
		};
		UpdateRef            ref      = new UpdateRef(original, removeAction);
		WrappedEventListener listener = new WrappedEventListener(ref);
		box[0] = listener;
		return listener;
	}

	/**
	 * 包装 {@link Element#addCaptureListener(EventListener)} 捕获阶段监听器。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：{@code element.removeCaptureListener(ref)}，仅注销当前捕获监听器。
	 * @param element  目标 UI 节点
	 * @param original 原始监听器
	 * @return 具备局部熔断保护的代理 EventListener
	 */
	public static EventListener wrapCaptureListener(Element element, EventListener original) {
		if (original == null) return null;
		if (original instanceof WrappedRef wr) {
			if (element != null) {
				wr.getUpdateRef().addOnRemove(() -> element.removeCaptureListener(original));
			}
			return original;
		}
		EventListener[] box = new EventListener[1];
		Runnable removeAction = () -> {
			if (element != null && box[0] != null) element.removeCaptureListener(box[0]);
		};
		UpdateRef            ref      = new UpdateRef(original, removeAction);
		WrappedEventListener listener = new WrappedEventListener(ref);
		box[0] = listener;
		return listener;
	}

	/**
	 * 在 {@link Element#removeListener(EventListener)} 入口反查被包装的 {@link WrappedEventListener} 实例。
	 * Arc 的 removeListener 使用引用全等 (==) 判定，若传入原始监听器则无法匹配内部存入的包装器。
	 * 此方法返回列表中实际存在的包装实例，未匹配则原样返回。
	 * @param element  宿主 Element
	 * @param listener 待移除的监听器（原始或包装器）
	 * @return 列表中实际存在的监听器实例
	 */
	public static EventListener unwrapListener(Element element, EventListener listener) {
		if (element == null || listener == null) return listener;
		var listeners = element.getListeners();
		if (listeners == null) return listener;
		for (int i = 0; i < listeners.size; i++) {
			EventListener item = listeners.get(i);
			if (item == listener) return item;
			if (item instanceof WrappedRef wr && (wr.getOriginal() == listener || Objects.equals(wr.getOriginal(), listener))) {
				return item;
			}
		}
		return listener;
	}

	/**
	 * 在 {@link Element#removeCaptureListener(EventListener)} 入口反查被包装的 {@link WrappedEventListener} 实例。
	 * @param element  宿主 Element
	 * @param listener 待移除的捕获监听器（原始或包装器）
	 * @return 列表中实际存在的监听器实例
	 */
	public static EventListener unwrapCaptureListener(Element element, EventListener listener) {
		if (element == null || listener == null) return listener;
		var listeners = element.getCaptureListeners();
		if (listeners == null) return listener;
		for (int i = 0; i < listeners.size; i++) {
			EventListener item = listeners.get(i);
			if (item == listener) return item;
			if (item instanceof WrappedRef wr && (wr.getOriginal() == listener || Objects.equals(wr.getOriginal(), listener))) {
				return item;
			}
		}
		return listener;
	}

	// Cell 专用

	/**
	 * 包装 {@link Cell#update(Cons)} 布局单元每帧消费回调。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：{@code cell.update(null)}。
	 * @param cell     目标表格单元
	 * @param original 原始单元更新回调
	 * @return 具备局部熔断保护的代理 Cons
	 */
	public static Cons<?> wrapCellUpdate(Cell<?> cell, Cons<?> original) {
		if (original == null) return null;
		Runnable removeAction = () -> {
			if (cell != null) cell.update(null);
		};
		if (original instanceof WrappedRef wr) {
			wr.getUpdateRef().addOnRemove(removeAction);
			return original;
		}
		return new WrappedCons<>(new UpdateRef(original, removeAction));
	}

	/**
	 * 包装 {@link Cell#disabled(Boolf)} 布局单元禁用条件。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：{@code cell.disabled(null)}。
	 * @param cell     目标表格单元
	 * @param original 原始禁用断言
	 * @return 具备局部熔断保护的代理 Boolf
	 */
	public static Boolf<?> wrapCellDisabled(Cell<?> cell, Boolf<?> original) {
		if (original == null) return null;
		Runnable removeAction = () -> {
			if (cell != null) cell.disabled(null);
		};
		if (original instanceof WrappedRef wr) {
			wr.getUpdateRef().addOnRemove(removeAction);
			return original;
		}
		return new WrappedBoolf<>(new UpdateRef(original, removeAction), t -> {
			Element element = cell.get();
			if (element instanceof Disableable) {
				return ((Disableable) element).isDisabled();
			}
			return false;
		});
	}

	/**
	 * 包装 {@link Cell#tooltip(Cons)} 浮动提示构建回调。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：{@code cell.tooltip((Cons) null)}。
	 * @param cell     目标表格单元
	 * @param original 原始提示构建回调
	 * @return 具备局部熔断保护的代理 Cons
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static Cons<?> wrapCellTooltip(Cell<?> cell, Cons<?> original) {
		if (original == null) return null;
		Runnable removeAction = () -> {
			if (cell != null) cell.tooltip((Cons) null);
		};
		if (original instanceof WrappedRef wr) {
			wr.getUpdateRef().addOnRemove(removeAction);
			return original;
		}
		return new WrappedCons<>(new UpdateRef(original, removeAction));
	}

	/**
	 * 包装 {@link Cell#checked(Boolf)} 布局单元选中状态断言。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：{@code cell.checked(null)}。
	 * @param cell     目标表格单元
	 * @param original 原始选中断言
	 * @return 具备局部熔断保护的代理 Boolf
	 */
	public static Boolf<?> wrapCellChecked(Cell<?> cell, Boolf<?> original) {
		if (original == null) return null;
		Runnable removeAction = () -> {
			if (cell != null) cell.checked(null);
		};
		if (original instanceof WrappedRef wr) {
			wr.getUpdateRef().addOnRemove(removeAction);
			return original;
		}
		return new WrappedBoolf<>(new UpdateRef(original, removeAction), t -> {
			Element element = cell.get();
			if (element instanceof Button) {
				return ((Button) element).isChecked();
			}
			return false;
		});
	}

	// 事件静默熔断专用（针对 clicked, hovered 等事件回调，报错仅停止回调，不删元素）

	/**
	 * 包装瞬时交互事件（如 {@code clicked}、{@code hovered}、弹窗生命周期等）。
	 * 发生 {@link LinkageError} 时仅清空内部引用静音失效，绝不调用任何外部删除动作，零 Element 引用。
	 * @param element  宿主 Element
	 * @param original 原始 Runnable 实例
	 * @return 具备静默熔断保护的代理 Runnable
	 */
	public static Runnable wrapSilent(Element element, Runnable original) {
		return wrap(element, original, NOOP);
	}

	/**
	 * 包装瞬时消费事件回调。发生 {@link LinkageError} 时仅清空内部引用静音失效。
	 * @param element  宿主 Element
	 * @param original 原始 Cons 实例
	 * @return 具备静默熔断保护的代理 Cons
	 */
	public static Cons<?> wrapSilent(Element element, Cons<?> original) {
		return wrap(element, original, NOOP);
	}

	/**
	 * 包装瞬时单浮点手势/滚动回调。发生 {@link LinkageError} 时仅清空内部引用静音失效。
	 * @param element  宿主 Element
	 * @param original 原始 Floatc 实例
	 * @return 具备静默熔断保护的代理 Floatc
	 */
	public static Floatc wrapSilent(Element element, Floatc original) {
		return wrap(element, original, NOOP);
	}

	/**
	 * 包装瞬时双浮点拖拽手势回调。发生 {@link LinkageError} 时仅清空内部引用静音失效。
	 * @param element  宿主 Element
	 * @param original 原始 Floatc2 实例
	 * @return 具备静默熔断保护的代理 Floatc2
	 */
	public static Floatc2 wrapSilent(Element element, Floatc2 original) {
		return wrap(element, original, NOOP);
	}

	//endregion

	//region Events 事件总线包装支持

	private static volatile ObjectMap<Object, Seq<Cons<?>>> eventsMap;

	/**
	 * 获取 {@link Events#events} 事件注册表（由 ASM 重写的 Events 方法在运行时通过 GETSTATIC 自举传递）。
	 * @return Events 内部维护的事件映射表，若尚未自举接收则返回 null
	 */
	public static ObjectMap<Object, Seq<Cons<?>>> getEventsMap() {
		return eventsMap;
	}

	/**
	 * 供字节码或自举流程直接注入 {@link Events#events} 引用，实现 100% 零反射访问。
	 * @param map Events 内部维护的事件注册表
	 */
	public static void setEventsMap(ObjectMap<Object, Seq<Cons<?>>> map) {
		eventsMap = map;
	}

	private static final Seq<Runnable> DEFERRED_REMOVALS = new Seq<>();

	private static void deferRemoval(Runnable r) {
		if (r == null || r == NOOP || r == REMOVED) return;
		synchronized (DEFERRED_REMOVALS) {
			DEFERRED_REMOVALS.add(r);
		}
	}

	/**
	 * 清理在无主循环环境（如 Core.app == null）下暂存的熔断注销动作。
	 * 在监视器锁外执行回调，彻底消除持有锁调用外部代码与死锁风险。
	 */
	public static void flushDeferredRemovals() {
		Seq<Runnable> toRun;
		synchronized (DEFERRED_REMOVALS) {
			if (DEFERRED_REMOVALS.isEmpty()) return;
			toRun = new Seq<>(DEFERRED_REMOVALS);
			DEFERRED_REMOVALS.clear();
		}
		for (int i = 0; i < toRun.size; i++) {
			executeRemove(toRun.get(i));
		}
	}

	/**
	 * 包装 {@link Cons} 的事件监听器容器，支持与原始被代理引用的等价比较与注销。
	 */
	public static class EventCons<T> implements Cons<T>, WrappedRef {
		public final  UpdateRef ref;
		private final Cons<T>   key;

		public EventCons(Cons<T> original, Runnable onRemove) {
			this.ref = new UpdateRef(original, onRemove);
			this.key = original;
		}

		@Override
		public void get(T t) {
			ref.runCons(t);
		}

		@Override
		public UpdateRef getUpdateRef() {
			return ref;
		}

		public Cons<T> getKey() {
			return key;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			EventCons<?> other = (EventCons<?>) o;
			return Objects.equals(key, other.key);
		}

		@Override
		public int hashCode() {
			return key != null ? key.hashCode() : 0;
		}
	}

	/**
	 * 包装 {@link Runnable} 的事件监听器容器（将 Runnable 适配为 Cons<Object> 以供 Events 内部注册表存储），
	 * 并在熔断时通过私有注册表将自身精准注销。
	 */
	public static class EventRunnableCons implements Cons<Object>, WrappedRef {
		public final  UpdateRef ref;
		private final Runnable  key;

		public EventRunnableCons(Runnable original, Runnable onRemove) {
			this.ref = new UpdateRef(original, onRemove);
			this.key = original;
		}

		@Override
		public void get(Object param) {
			ref.run();
		}

		@Override
		public UpdateRef getUpdateRef() {
			return ref;
		}

		public Runnable getKey() {
			return key;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			EventRunnableCons other = (EventRunnableCons) o;
			return Objects.equals(key, other.key);
		}

		@Override
		public int hashCode() {
			return key != null ? key.hashCode() : 0;
		}
	}

	/**
	 * 代理 {@link Events#on(Class, Cons)}，由 ASM 重写的方法通过原生 GETSTATIC 指令自举传入 events 注册表。
	 * @param type     事件类型 Class
	 * @param listener 事件消费回调
	 * @param map      Events 内部私有事件注册表（100% 零反射原生自举传入）
	 * @param <T>      事件类型
	 */
	@SuppressWarnings("unchecked")
	public static <T> void eventsOn(Class<T> type, Cons<T> listener, ObjectMap<Object, Seq<Cons<?>>> map) {
		if (listener == null) return;
		if (map == null) {
			HotSwapAgent.error("[UpdateRef] eventsOn: Events.events registry map is null! Failed to register event listener for: " + type);
			throw new IllegalStateException("[UpdateRef] Events.events registry map is null");
		}
		if (eventsMap != map) eventsMap = map;
		flushDeferredRemovals();

		if (listener instanceof EventCons) {
			map.get(type, () -> new Seq<>(Cons.class)).add(listener);
			return;
		}

		EventCons<T>[] box = (EventCons<T>[]) new EventCons[1];
		Runnable onRemove = () -> {
			if (box[0] != null) {
				Seq<Cons<?>> s = map.get(type);
				if (s != null) {
					s.remove(box[0], true);
				}
			}
		};

		EventCons<T> wrapper = new EventCons<>(listener, onRemove);
		box[0] = wrapper;
		map.get(type, () -> new Seq<>(Cons.class)).add(wrapper);
	}

	/**
	 * 代理 {@link Events#run(Object, Runnable)}，由 ASM 重写的方法通过原生 GETSTATIC 指令自举传入 events 注册表。
	 * @param type     事件类型（Class 或 Enum Trigger 等）
	 * @param listener 运行回调
	 * @param map      Events 内部私有事件注册表（100% 零反射原生自举传入）
	 */
	@SuppressWarnings("rawtypes")
	public static void eventsRun(Object type, Runnable listener, ObjectMap<Object, Seq<Cons<?>>> map) {
		if (listener == null) return;
		if (map == null) {
			HotSwapAgent.error("[UpdateRef] eventsRun: Events.events registry map is null! Failed to register event listener for: " + type);
			throw new IllegalStateException("[UpdateRef] Events.events registry map is null");
		}
		if (eventsMap != map) eventsMap = map;
		flushDeferredRemovals();

		if (listener instanceof EventRunnableCons) {
			map.get(type, () -> new Seq<>(Cons.class)).add((Cons) listener);
			return;
		}

		EventRunnableCons[] box = new EventRunnableCons[1];
		Runnable onRemove = () -> {
			if (box[0] != null) {
				Seq<Cons<?>> s = map.get(type);
				if (s != null) {
					s.remove(box[0], true);
				}
			}
		};

		EventRunnableCons wrapper = new EventRunnableCons(listener, onRemove);
		box[0] = wrapper;
		map.get(type, () -> new Seq<>(Cons.class)).add(wrapper);
	}

	/**
	 * 代理 {@link Events#remove(Class, Cons)}，支持解包匹配并注销已被包装的事件监听器。
	 * @param type     事件类型 Class
	 * @param listener 待注销的监听器（可以是原始 listener，也可以是 EventCons 代理实例）
	 * @param map      Events 内部私有事件注册表（100% 零反射原生自举传入）
	 * @param <T>      事件类型
	 * @return 若成功找到并注销返回 true，否则返回 false
	 */
	public static <T> boolean eventsRemove(Class<T> type, Cons<T> listener, ObjectMap<Object, Seq<Cons<?>>> map) {
		if (listener == null) return false;
		if (map != null && eventsMap != map) eventsMap = map;
		if (map == null) return false;
		flushDeferredRemovals();
		Seq<Cons<?>> seq = map.get(type);
		if (seq == null) return false;

		Object targetKey = listener instanceof EventCons<?> ec ? ec.getKey() :
		                   (listener instanceof EventRunnableCons erc ? erc.getKey() : listener);

		for (int i = 0; i < seq.size; i++) {
			Cons<?> item = seq.items[i];
			if (item == listener || item == targetKey) {
				seq.remove(i);
				return true;
			}
			if (item instanceof EventCons<?> ec && (ec.getKey() == targetKey || Objects.equals(ec.getKey(), targetKey))) {
				seq.remove(i);
				return true;
			}
			if (item instanceof EventRunnableCons erc && (erc.getKey() == targetKey || Objects.equals(erc.getKey(), targetKey))) {
				seq.remove(i);
				return true;
			}
			if (Objects.equals(item, targetKey)) {
				seq.remove(i);
				return true;
			}
		}
		return false;
	}

	/**
	 * 注销通过 {@link Events#run(Object, Runnable)} 注册的监听器。
	 * @param type     事件类型
	 * @param listener 原始 Runnable 实例
	 * @return 若成功找到并注销返回 true，否则返回 false
	 */
	public static boolean removeEventRun(Object type, Runnable listener) {
		if (listener == null) return false;
		ObjectMap<Object, Seq<Cons<?>>> map = eventsMap;
		if (map == null) return false;
		flushDeferredRemovals();
		Seq<Cons<?>> seq = map.get(type);
		if (seq == null) return false;

		Object targetKey = listener instanceof EventRunnableCons erc ? erc.getKey() : listener;

		for (int i = 0; i < seq.size; i++) {
			Cons<?> item = seq.items[i];
			if (item == listener || item == targetKey) {
				seq.remove(i);
				return true;
			}
			if (item instanceof EventRunnableCons erc && (erc.getKey() == targetKey || Objects.equals(erc.getKey(), targetKey))) {
				seq.remove(i);
				return true;
			}
			if (item instanceof EventCons<?> ec && (ec.getKey() == targetKey || Objects.equals(ec.getKey(), targetKey))) {
				seq.remove(i);
				return true;
			}
			if (Objects.equals(item, targetKey)) {
				seq.remove(i);
				return true;
			}
		}
		return false;
	}

	//endregion

	/**
	 * 检查原始函数引用是否已被清空。
	 * 若为 null，说明已发生热重载异常或已被清理，若尚未投递熔断动作则仅投递一次，并返回 true 以便短路跳过执行。
	 * @param f 目标函数对象
	 * @return true 表示已被清空，当前执行应短路中断
	 */
	private boolean checkFn(Object f) {
		if (f == null) {
			// fn 已被清空（HotSwap 删除或 NoSuchMethodError 兜底），若尚未投递熔断动作则仅触发一次
			triggerRemove();
			return true;
		}
		return false;
	}

	/**
	 * 执行被代理的 {@link Runnable}。捕获 {@link LinkageError} 并转入熔断处理。
	 */
	public void run() {
		var f = (Runnable) this.fn;
		if (checkFn(f)) return;
		try {
			f.run();
		} catch (LinkageError e) {
			onLinkageError(f, e);
		}
	}

	/** 清空被代理的原始函数引用，切断死代码调用 */
	private void clearFn() {
		this.fn = null;
	}

	/**
	 * 执行被代理的 {@link Prov}。捕获 {@link LinkageError} 并转入熔断处理，熔断后返回 null。
	 */
	@SuppressWarnings("unchecked")
	public <T> T runProv() {
		var f = (Prov<T>) this.fn;
		if (checkFn(f)) return null;
		try {
			return f.get();
		} catch (LinkageError e) {
			onLinkageError(f, e);
			return null;
		}
	}

	/**
	 * 执行被代理的 {@link Boolp}。捕获 {@link LinkageError} 并转入熔断处理，熔断后返回 false。
	 */
	public boolean runBoolp() {
		return runBoolp(null);
	}

	/**
	 * 执行被代理的 {@link Boolp}。捕获 {@link LinkageError} 并转入熔断处理，熔断后执行兜底逻辑。
	 * @param fallback 熔断发生或引用清空时的兜底提供器
	 * @return 运行结果或兜底结果
	 */
	public boolean runBoolp(Boolp fallback) {
		var f = (Boolp) this.fn;
		if (checkFn(f)) return fallback != null && fallback.get();
		try {
			return f.get();
		} catch (LinkageError e) {
			onLinkageError(f, e);
			return fallback != null && fallback.get();
		}
	}

	/**
	 * 执行被代理的 {@link Cons}。捕获 {@link LinkageError} 并转入熔断处理。
	 */
	@SuppressWarnings("unchecked")
	public <T> void runCons(T t) {
		var f = (Cons<T>) this.fn;
		if (checkFn(f)) return;
		try {
			f.get(t);
		} catch (LinkageError e) {
			onLinkageError(f, e);
		}
	}

	/**
	 * 执行被代理的 {@link Boolf}。捕获 {@link LinkageError} 并转入熔断处理，熔断后返回 false。
	 */
	public <T> boolean runBoolf(T t) {
		return runBoolf(t, null);
	}

	/**
	 * 执行被代理的 {@link Boolf}。捕获 {@link LinkageError} 并转入熔断处理，熔断后执行兜底逻辑。
	 * @param t        入参
	 * @param fallback 熔断发生或引用清空时的兜底断言
	 * @return 运行结果或兜底结果
	 */
	@SuppressWarnings("unchecked")
	public <T> boolean runBoolf(T t, Boolf<T> fallback) {
		var f = (Boolf<T>) this.fn;
		if (checkFn(f)) return fallback != null && fallback.get(t);
		try {
			return f.get(t);
		} catch (LinkageError e) {
			onLinkageError(f, e);
			return fallback != null && fallback.get(t);
		}
	}

	/**
	 * 执行被代理的 {@link Floatc}。捕获 {@link LinkageError} 并转入熔断处理。
	 */
	public void runFloatc(float f) {
		var fn = (Floatc) this.fn;
		if (checkFn(fn)) return;
		try {
			fn.get(f);
		} catch (LinkageError e) {
			onLinkageError(fn, e);
		}
	}

	/**
	 * 执行被代理的 {@link Floatc2}。捕获 {@link LinkageError} 并转入熔断处理。
	 */
	public void runFloatc2(float f1, float f2) {
		var fn = (Floatc2) this.fn;
		if (checkFn(fn)) return;
		try {
			fn.get(f1, f2);
		} catch (LinkageError e) {
			onLinkageError(fn, e);
		}
	}

	/**
	 * 执行被代理的 {@link TextFieldValidator}。捕获 {@link LinkageError} 并转入熔断处理，熔断后返回 false。
	 */
	public boolean runValidator(String t) {
		return runValidator(t, null);
	}

	/**
	 * 执行被代理的 {@link TextFieldValidator}。捕获 {@link LinkageError} 并转入熔断处理，熔断后执行兜底逻辑。
	 * @param t        输入文本
	 * @param fallback 熔断发生或引用清空时的兜底验证器
	 * @return 验证结果或兜底结果
	 */
	public boolean runValidator(String t, TextFieldValidator fallback) {
		var f = (TextFieldValidator) this.fn;
		if (checkFn(f)) return fallback != null && fallback.valid(t);
		try {
			return f.valid(t);
		} catch (LinkageError e) {
			onLinkageError(f, e);
			return fallback != null && fallback.valid(t);
		}
	}

	/**
	 * 执行被代理的 {@link EventListener}。捕获 {@link LinkageError} 并转入熔断处理，熔断后返回 false。
	 */
	public boolean runEventListener(SceneEvent eventType) {
		var f = (EventListener) this.fn;
		if (checkFn(f)) return false;
		try {
			return f.handle(eventType);
		} catch (LinkageError e) {
			onLinkageError(f, e);
			return false;
		}
	}

	/**
	 * 查询当前代理是否已触发熔断清理流程。
	 * @return 若熔断清理动作已投递或已执行则返回 true
	 */
	public boolean isRemoved() {
		return onRemove == REMOVED;
	}

	/**
	 * 判断给定的 Throwable 是否属于热重载结构变更引起的链接异常（类/方法/字段不存在或签名不兼容）。
	 * 对于非热重载引发的错误（如 {@link ExceptionInInitializerError} 静态块异常或 {@link VerifyError} 等）应正常抛出，避免吞掉业务异常。
	 * @param t 目标异常
	 * @return 若为热重载引起的结构缺失异常则返回 true
	 */
	public static boolean isHotSwapLinkageError(Throwable t) {
		return t instanceof IncompatibleClassChangeError
		       || t instanceof NoClassDefFoundError;
	}

	/**
	 * 触发精准熔断清理流程（单实例仅触发一次）。
	 * 采用 {@code onRemove == REMOVED} 哨兵机制替代 boolean 状态标志，
	 * 在触发瞬间断开对清理闭包的引用，并按需向主线程队列投递执行任务。
	 */
	private void triggerRemove() {
		if (this.onRemove == REMOVED) return;
		Runnable r;
		synchronized (this) {
			if (this.onRemove == REMOVED) return;
			r = this.onRemove;
			this.onRemove = REMOVED;
		}
		if (r != null && r != NOOP) {
			if (Core.app != null) {
				try {
					Core.app.post(() -> executeRemove(r));
					return;
				} catch (Throwable t) {
					HotSwapAgent.error("[UpdateRef] Core.app.post failed, fallback to deferred removal: " + t.getMessage(), t);
				}
			}
			deferRemoval(r);
		}
	}

	private static void executeRemove(Runnable r) {
		try {
			r.run();
		} catch (Throwable t) {
			HotSwapAgent.error("[UpdateRef] onRemove failed: " + t.getMessage(), t);
		}
	}

	/**
	 * 处理 LinkageError（热重载导致方法不存在或签名不兼容）：
	 * 1) 仅针对热重载签名/符号缺失错误熔断，其余错误（如静态初始化异常）直接重新抛出；
	 * 2) 打印诊断错误日志与完整异常堆栈；
	 * 3) 立即置空 {@code fn} 停止后续调用；
	 * 4) 触发精准熔断清理 {@link #triggerRemove()}（具备 REMOVED 哨兵防抖，单实例仅投递一次）。
	 * @param f 发生故障的原始函数实例
	 * @param e 捕获的链接错误异常
	 */
	private void onLinkageError(Object f, LinkageError e) {
		if (!isHotSwapLinkageError(e)) {
			throw e;
		}
		HotSwapAgent.error("[UpdateRef] LinkageError (" + e.getClass().getSimpleName() + ") from "
		                   + (f == null ? "?" : f.getClass().getName()) + ": " + e.getMessage(), e);
		clearFn();
		triggerRemove();
	}

}