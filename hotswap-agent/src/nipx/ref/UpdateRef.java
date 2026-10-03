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

import java.lang.reflect.Field;
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
 *   <li><b>精准局部熔断：</b>通过 {@code onRemove} 清理动作提供细粒度的资源注销与置空（例如 {@code el.update(null)}、{@code el.removeListener(ref)}），
 *       确保故障仅在局部隔离，绝不波及宿主组件的正常渲染与展示。</li>
 *   <li><b>Events 事件总线熔断：</b>底层重写 {@link arc.Events#on} / {@link arc.Events#run}，通过原生字节码自举直传私有注册表；
 *       监听器失效时通过注册表将失效监听器即刻注销，阻断 60FPS 高频事件（如 {@code Trigger.update}）死循环与泄漏，且异步解绑保护内部遍历安全。</li>
 *   <li><b>统一延迟清理队列：</b>所有熔断动作统一收敛至线程安全的入队排重队列（{@code DEFERRED_REMOVALS}），
 *       通过门闩标志位向主线程安全点（{@code Core.app.post}）进行单次批处理调度，杜绝并发冲突、遍历中修改与空转 post 开销；
 *       同时配合生命周期交接点（如 {@code ClientLoadEvent}）主动保底冲刷，防止引擎启动期暂存的任务滞留。</li>
 *   <li><b>细粒度闭包释放：</b>针对局部熔断捕获的 UI 节点与回调闭包，在触发熔断时通过将 {@code fn} 与 {@code original} 一并置空，
 *       瞬时切断对原始闭包的强引用，零额外对象开销，对 GC 极度友好。</li>
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
@SuppressWarnings({"rawtypes", "unchecked", "unused"})
public class UpdateRef {

	/** 空操作常量，用于事件回调发生异常时的静默熔断，防止触发任何外部破坏性清理 */
	public static final Runnable NOOP = () -> { };

	/** 标记熔断清理已被触发的哨兵对象，替代原有的 boolean removed 标志，兼顾状态判定与闭包引用释放 */
	private static final Runnable REMOVED = () -> {
		throw new UnsupportedOperationException();
	};

	/**
	 * 复合清理动作容器，通过 {@link #actions} 维护清理链并在追加时自动基于 {@link Object#equals} 去重。
	 * <p>
	 * <b>关于动作去重行为说明：</b><br>
	 * 由于各类 {@code Wrapped*} 代理包装器（如 {@link WrappedRunnable}）以及具体动作 Record（如 {@code RemoveListenerAction}）
	 * 的 {@code equals} 均以底层委托实例或属性值为依据，若向同一包装引用或上下文追加多个等价动作（例如针对同一 element 和 wrapper
	 * 重复构造的清理 Record），合并后将仅保留首个动作。此设计有效防止了重复包装造成的闭包堆积；
	 * 但若业务代码显式依赖于“两个等价动作均被独立执行”（例如为了统计或日志完整性），需注意等价实例会被去重合并。
	 * </p>
	 */
	public static class CompositeAction implements Runnable {
		final Seq<Runnable> actions = new Seq<>(2);
		private volatile boolean executed;

		public CompositeAction(Runnable first) {
			if (first != null && first != NOOP && first != REMOVED) {
				if (first instanceof CompositeAction ca) {
					actions.addAll(ca.actions);
				} else {
					actions.add(first);
				}
			}
		}

		void add(Runnable r) {
			if (r == null || r == NOOP || r == REMOVED) return;
			if (r instanceof CompositeAction ca) {
				for (int i = 0; i < ca.actions.size; i++) {
					add(ca.actions.get(i));
				}
				return;
			}
			if (!actions.contains(r, false)) {
				actions.add(r);
			}
		}

		@Override
		public void run() {
			if (executed) return;
			synchronized (this) {
				if (executed) return;
				executed = true;
			}
			for (int i = 0; i < actions.size; i++) {
				executeRemove(actions.get(i));
			}
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o instanceof CompositeAction ca) {
				return Objects.equals(actions, ca.actions);
			}
			return false;
		}

		@Override
		public int hashCode() {
			return actions.hashCode();
		}
	}

	/** 原始函数式接口实例（熔断时与 fn 一同置空切断闭包引用），供解包、比较与哈希 */
	private volatile Object                original;
	/** 原始函数式接口的哈希码缓存，保证对象在熔断与置空前后 hashCode 恒定不变 */
	private final    int                   originalHash;
	/** 当前被代理的目标函数式接口实例（例如 {@link Runnable}、{@link Cons} 等）；发生异常或注销后置为 null */
	private volatile Object                fn;
	/** 自定义熔断/销毁动作；为 null 时表示静默失效；为 {@link #REMOVED} 时表示已触发熔断清理 */
	private volatile Runnable              onRemove;

	/** 线程本地上下文，支持通过 {@link #withOnRemove} 跨调用栈隐式传递熔断清理回调 */
	private static final ThreadLocal<Runnable> CONTEXT_ON_REMOVE = new ThreadLocal<>();

	/**
	 * 合并两个清理动作。按序执行，任何一方抛出异常均被隔离捕获并记录日志，不影响后续清理。
	 * 永远构造并返回新的 CompositeAction 实例，严禁原地修改传入动作，避免污染共享的上下文。
	 * @param a 首要清理动作
	 * @param b 次要/上下文清理动作
	 * @return 合并后的单一动作
	 */
	public static Runnable combine(Runnable a, Runnable b) {
		if (a == null || a == NOOP || a == REMOVED) return b;
		if (b == null || b == NOOP || b == REMOVED) return a;
		if (Objects.equals(a, b)) return a;
		CompositeAction composite = new CompositeAction(a);
		composite.add(b);
		return composite;
	}

	/**
	 * 初始化包装引用与熔断动作，自动与当前线程上下文清理动作（{@link #CONTEXT_ON_REMOVE}）合并。
	 * 特别地：若显式指定为 {@link #NOOP}，则显式屏蔽上下文继承，确保严格静音失效。
	 * @param original 原始函数式接口实例
	 * @param onRemove 显式指定的清理动作
	 */
	private UpdateRef(Object original, Runnable onRemove) {
		this.original = original;
		this.originalHash = original != null ? original.hashCode() : 0;
		this.fn = original;
		this.onRemove = onRemove == NOOP ? NOOP : combine(onRemove, CONTEXT_ON_REMOVE.get());
	}

	/**
	 * 获取被代理的原始函数式接口对象（若已触发熔断清理则返回 null）。
	 */
	public Object getOriginal() {
		return original;
	}

	/**
	 * 获取原始函数式接口对象的哈希码缓存。
	 */
	public int getOriginalHash() {
		return originalHash;
	}

	/**
	 * 获取当前配置的熔断清理动作。
	 * <p>
	 * <b>重要说明：</b>此方法返回值仅供调试与诊断观测使用，<b>绝对严禁外部手动调用 {@link Runnable#run()}</b>。<br>
	 * 包装引用的熔断清理具有严格的状态机与幂等生命周期管理，手动调用将绕过内部熔断流转，
	 * 破坏清理状态一致性。
	 * </p>
	 * @return 当前绑定的清理动作，若已熔断或未设置则返回 null
	 */
	public Runnable getOnRemove() {
		Runnable r = onRemove;
		return r == REMOVED ? null : r;
	}

	/**
	 * 动态设置或替换当前包装引用的熔断清理动作。
	 * 若当前已处于熔断状态（{@code onRemove == REMOVED}）或静音模式（{@code onRemove == NOOP}），则忽略此设置；
	 * 严禁传入内部哨兵对象伪造已熔断状态。
	 * @param onRemove 新的清理动作
	 */
	public void setOnRemove(Runnable onRemove) {
		if (onRemove == REMOVED) {
			throw new IllegalArgumentException("Cannot manually set onRemove to REMOVED sentinel");
		}
		synchronized (this) {
			if (this.onRemove != REMOVED && this.onRemove != NOOP) {
				this.onRemove = onRemove;
			}
		}
	}

	/**
	 * 为当前包装引用追加合并新的清理动作。
	 * 若当前已处于熔断状态（{@code onRemove == REMOVED}）或静音模式（{@code onRemove == NOOP}），则拒绝并返回 false。
	 * @param action 待追加的清理动作
	 * @return 若成功追加返回 true；若已被拒绝（已熔断、静音模式或 action 无效）返回 false
	 */
	public boolean addOnRemove(Runnable action) {
		if (action == null || action == NOOP || action == REMOVED) return false;
		synchronized (this) {
			if (this.onRemove != REMOVED && this.onRemove != NOOP) {
				this.onRemove = combine(this.onRemove, action);
				return true;
			}
		}
		return false;
	}

	/**
	 * 在指定代码块的作用域内设置默认的 {@code onRemove} 动作。
	 * 该作用域内由 {@link UpdateRef} 创建且未显式指定清理动作的包装实例，将自动继承此动作。
	 * <p>
	 * <b>关于动作幂等性说明：</b><br>
	 * 由于同一作用域内可能创建多个被代理的回调引用（例如同一个 UI 组件内的多个 update/visible 回调），
	 * 它们会共享继承同一个上下文清理动作。当其中任意一个回调发生异常触发熔断时，该清理动作即会被执行；
	 * 因此传入的 {@code onRemoveAction} 建议具备<b>幂等性</b>（即支持安全地被多次调用，或在首次执行后自行置空状态）。
	 * </p>
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
	 * <p>
	 * <b>关于动作幂等性说明：</b><br>
	 * 由于同一作用域内可能创建多个被代理的回调引用，它们会共享继承同一个上下文清理动作；
	 * 传入的 {@code onRemoveAction} 建议具备<b>幂等性</b>。
	 * </p>
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

		default int getOriginalHash() {
			UpdateRef ref = getUpdateRef();
			return ref != null ? ref.getOriginalHash() : 0;
		}

		/**
		 * 统一比对两个包装代理实例的等价性。
		 * 仅当两者的运行时类型一致，且底层的原始委托实例均未被熔断置空且逻辑相等时才判为相等。
		 * 若任一方已被熔断置空，则退化为基于引用全等 (==) 的判定，杜绝因两个 null 产生误判。
		 */
		static boolean equals(WrappedRef a, Object b) {
			if (a == b) return true;
			if (b == null || a.getClass() != b.getClass()) return false;
			Object origA = a.getOriginal();
			Object origB = ((WrappedRef) b).getOriginal();
			return origA != null && origA.equals(origB);
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
			return WrappedRef.equals(this, o);
		}

		@Override
		public int hashCode() {
			return getOriginalHash();
		}
	}

	public static class WrappedProv<T> implements Prov<T>, WrappedRef {
		private final    UpdateRef ref;
		private volatile Prov<T>   fallback;

		public WrappedProv(UpdateRef ref) {
			this(ref, null);
		}

		public WrappedProv(UpdateRef ref, Prov<T> fallback) {
			this.ref = ref;
			this.fallback = fallback;
		}

		public void setFallbackIfAbsent(Prov<T> fallback) {
			if (this.fallback == null && fallback != null) {
				this.fallback = fallback;
			}
		}

		@Override
		public T get() {
			return ref.runProv(fallback);
		}

		@Override
		public UpdateRef getUpdateRef() {
			return ref;
		}

		@Override
		public boolean equals(Object o) {
			return WrappedRef.equals(this, o);
		}

		@Override
		public int hashCode() {
			return getOriginalHash();
		}
	}

	public static class WrappedBoolp implements Boolp, WrappedRef {
		private final    UpdateRef ref;
		private volatile Boolp     fallback;

		public WrappedBoolp(UpdateRef ref) {
			this(ref, null);
		}

		public WrappedBoolp(UpdateRef ref, Boolp fallback) {
			this.ref = ref;
			this.fallback = fallback;
		}

		public void setFallbackIfAbsent(Boolp fallback) {
			if (this.fallback == null && fallback != null) {
				this.fallback = fallback;
			}
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
			return WrappedRef.equals(this, o);
		}

		@Override
		public int hashCode() {
			return getOriginalHash();
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
			return WrappedRef.equals(this, o);
		}

		@Override
		public int hashCode() {
			return getOriginalHash();
		}
	}

	public static class WrappedBoolf<T> implements Boolf<T>, WrappedRef {
		private final    UpdateRef ref;
		private volatile Boolf<T>  fallback;

		public WrappedBoolf(UpdateRef ref) {
			this(ref, null);
		}

		public WrappedBoolf(UpdateRef ref, Boolf<T> fallback) {
			this.ref = ref;
			this.fallback = fallback;
		}

		public void setFallbackIfAbsent(Boolf<T> fallback) {
			if (this.fallback == null && fallback != null) {
				this.fallback = fallback;
			}
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
			return WrappedRef.equals(this, o);
		}

		@Override
		public int hashCode() {
			return getOriginalHash();
		}
	}

	public static class WrappedValidator implements TextFieldValidator, WrappedRef {
		private final    UpdateRef          ref;
		private volatile TextFieldValidator fallback;

		public WrappedValidator(UpdateRef ref) {
			this(ref, null);
		}

		public WrappedValidator(UpdateRef ref, TextFieldValidator fallback) {
			this.ref = ref;
			this.fallback = fallback;
		}

		public void setFallbackIfAbsent(TextFieldValidator fallback) {
			if (this.fallback == null && fallback != null) {
				this.fallback = fallback;
			}
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
			return WrappedRef.equals(this, o);
		}

		@Override
		public int hashCode() {
			return getOriginalHash();
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
			return WrappedRef.equals(this, o);
		}

		@Override
		public int hashCode() {
			return getOriginalHash();
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
			return WrappedRef.equals(this, o);
		}

		@Override
		public int hashCode() {
			return getOriginalHash();
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
			return WrappedRef.equals(this, o);
		}

		@Override
		public int hashCode() {
			return getOriginalHash();
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
			UpdateRef ref = wr.getUpdateRef();
			if (ref != null && ref.isRemoved()) return original;
			if (onRemove != null && ref != null) ref.addOnRemove(onRemove);
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
			UpdateRef ref = wr.getUpdateRef();
			if (ref != null && ref.isRemoved()) return original;
			if (onRemove != null && ref != null) ref.addOnRemove(onRemove);
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
			UpdateRef ref = wr.getUpdateRef();
			if (ref != null && ref.isRemoved()) return original;
			if (onRemove != null && ref != null) ref.addOnRemove(onRemove);
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
			UpdateRef ref = wr.getUpdateRef();
			if (ref != null && ref.isRemoved()) return original;
			if (onRemove != null && ref != null) ref.addOnRemove(onRemove);
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
			UpdateRef ref = wr.getUpdateRef();
			if (ref != null && ref.isRemoved()) return original;
			if (onRemove != null && ref != null) ref.addOnRemove(onRemove);
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
			UpdateRef ref = wr.getUpdateRef();
			if (ref != null && ref.isRemoved()) return original;
			if (onRemove != null && ref != null) ref.addOnRemove(onRemove);
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
			UpdateRef ref = wr.getUpdateRef();
			if (ref != null && ref.isRemoved()) return original;
			if (onRemove != null && ref != null) ref.addOnRemove(onRemove);
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
			UpdateRef ref = wr.getUpdateRef();
			if (ref != null && ref.isRemoved()) return original;
			if (onRemove != null && ref != null) ref.addOnRemove(onRemove);
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

	//region 槽位清理 Action Records 与反射辅助

	private static final Field ELEMENT_UPDATE_FIELD;
	private static final Field BUTTON_DISABLED_PROVIDER_FIELD;
	static {
		Field updateField = null;
		Field disabledField = null;
		try {
			updateField = Element.class.getDeclaredField("update");
			updateField.setAccessible(true);
		} catch (Throwable t) {
			HotSwapAgent.error("[UpdateRef] Failed to reflect Element.update field: " + t.getMessage(), t);
		}
		try {
			disabledField = Button.class.getDeclaredField("disabledProvider");
			disabledField.setAccessible(true);
		} catch (Throwable t) {
			HotSwapAgent.error("[UpdateRef] Failed to reflect Button.disabledProvider field: " + t.getMessage(), t);
		}
		ELEMENT_UPDATE_FIELD = updateField;
		BUTTON_DISABLED_PROVIDER_FIELD = disabledField;
	}

	public static Runnable getElementUpdate(Element element) {
		if (element == null || ELEMENT_UPDATE_FIELD == null) return null;
		try {
			return (Runnable) ELEMENT_UPDATE_FIELD.get(element);
		} catch (Throwable ignored) {
			return null;
		}
	}

	public static Boolp getButtonDisabledProvider(Button button) {
		if (button == null || BUTTON_DISABLED_PROVIDER_FIELD == null) return null;
		try {
			return (Boolp) BUTTON_DISABLED_PROVIDER_FIELD.get(button);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private record RemoveListenerAction(Element element, EventListener listener) implements Runnable {
		@Override
		public void run() {
			if (element != null && listener != null) element.removeListener(listener);
		}
	}

	private record RemoveCaptureListenerAction(Element element, EventListener listener) implements Runnable {
		@Override
		public void run() {
			if (element != null && listener != null) element.removeCaptureListener(listener);
		}
	}

	private record RemoveUpdateAction(Element element, Runnable target) implements Runnable {
		@Override
		public void run() {
			if (element != null && ELEMENT_UPDATE_FIELD != null && getElementUpdate(element) == target) {
				element.update(null);
			}
		}
	}

	private record RemoveVisibleAction(Element element, Boolp target) implements Runnable {
		@Override
		public void run() {
			if (element != null && element.visibility == target) {
				element.visible(null);
			}
		}
	}

	private record RemoveTouchableAction(Element element, Prov<?> target) implements Runnable {
		@Override
		public void run() {
			if (element != null && element.touchablility == target) {
				element.touchable(null);
			}
		}
	}

	private record RemoveButtonDisabledAction(Element element, Boolp target) implements Runnable {
		@Override
		public void run() {
			if (element instanceof Button b && BUTTON_DISABLED_PROVIDER_FIELD != null && getButtonDisabledProvider(b) == target) {
				b.setDisabled(null);
			}
		}
	}

	private record RemoveValidatorAction(Element element, TextFieldValidator target) implements Runnable {
		@Override
		public void run() {
			if (element instanceof TextField tf && tf.getValidator() == target) {
				tf.setValidator(null);
			}
		}
	}

	//endregion

	/**
	 * 将事件监听器 {@link EventListener} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 * 默认总会在异常时注销该监听器（{@code element.removeListener}），若传入了自定义 {@code onRemove} 则与之合并执行。
	 * @param element  宿主 Element
	 * @param original 原始监听器
	 * @param onRemove 发生 LinkageError 时的追加清理动作（可为 null）
	 * @return 具备容错保护的代理监听器
	 */
	public static EventListener wrap(Element element, EventListener original, Runnable onRemove) {
		if (original == null) return null;
		if (original instanceof WrappedRef wr) {
			UpdateRef ref = wr.getUpdateRef();
			if (ref != null && ref.isRemoved()) return original;
			if (element != null && ref != null) {
				ref.addOnRemove(new RemoveListenerAction(element, original));
			}
			if (onRemove != null && ref != null) ref.addOnRemove(onRemove);
			return original;
		}
		UpdateRef ref = new UpdateRef(original, onRemove);
		WrappedEventListener wrapper = new WrappedEventListener(ref);
		ref.addOnRemove(new RemoveListenerAction(element, wrapper));
		return wrapper;
	}

	//endregion

	//region 场景专用 wrap 方法（精准局部熔断）

	/**
	 * 包装 {@link Element#update(Runnable)} 回调。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：{@code element.update(null)}，仅移除该每帧更新回调，绝不删除 Element。
	 * 清理前会核对当前槽位是否仍为当前包装实例，避免误清后续新安装的回调。
	 * @param element  目标 UI 节点
	 * @param original 原始更新回调
	 * @return 具备局部熔断保护的代理 Runnable
	 */
	public static Runnable wrapUpdate(Element element, Runnable original) {
		if (original == null) return null;
		if (original instanceof WrappedRef wr) {
			UpdateRef ref = wr.getUpdateRef();
			if (ref != null && ref.isRemoved()) return original;
			if (element != null && ref != null) {
				ref.addOnRemove(new RemoveUpdateAction(element, original));
			}
			return original;
		}
		UpdateRef ref = new UpdateRef(original, null);
		WrappedRunnable wrapper = new WrappedRunnable(ref);
		ref.addOnRemove(new RemoveUpdateAction(element, wrapper));
		return wrapper;
	}

	/**
	 * 包装 {@link Element#visible(Boolp)} 条件回调。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：保持当前可见性状态并注销条件，绝不误清后续新回调。
	 * @param element  目标 UI 节点
	 * @param original 原始可见性提供器
	 * @return 具备局部熔断保护的代理 Boolp
	 */
	public static Boolp wrapVisible(Element element, Boolp original) {
		if (original == null) return null;
		if (original instanceof WrappedRef wr) {
			UpdateRef ref = wr.getUpdateRef();
			if (ref != null && ref.isRemoved()) return original;
			if (element != null && ref != null) {
				ref.addOnRemove(new RemoveVisibleAction(element, original));
			}
			if (original instanceof WrappedBoolp wb) {
				wb.setFallbackIfAbsent(() -> element == null || element.visible);
			}
			return original;
		}
		UpdateRef ref = new UpdateRef(original, null);
		WrappedBoolp wrapper = new WrappedBoolp(ref, () -> element == null || element.visible);
		ref.addOnRemove(new RemoveVisibleAction(element, wrapper));
		return wrapper;
	}

	/**
	 * 包装 {@link Element#touchable(Prov)} 条件回调。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：保持当前 Touchable 状态并注销条件，杜绝返回 null 导致 Element.act NPE。
	 * @param element  目标 UI 节点
	 * @param original 原始 Touchable 提供器
	 * @return 具备局部熔断保护的代理 Prov
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static Prov<?> wrapTouchable(Element element, Prov<?> original) {
		if (original == null) return null;
		if (original instanceof WrappedRef wr) {
			UpdateRef ref = wr.getUpdateRef();
			if (ref != null && ref.isRemoved()) return original;
			if (element != null && ref != null) {
				ref.addOnRemove(new RemoveTouchableAction(element, original));
			}
			if (original instanceof WrappedProv wp) {
				wp.setFallbackIfAbsent(() -> element != null ? element.touchable : Touchable.enabled);
			}
			return original;
		}
		Prov<Touchable> fallback = () -> element != null ? element.touchable : Touchable.enabled;
		UpdateRef ref = new UpdateRef(original, null);
		WrappedProv<Touchable> wrapper = new WrappedProv<>(ref, fallback);
		ref.addOnRemove(new RemoveTouchableAction(element, wrapper));
		return wrapper;
	}

	/**
	 * 包装 {@link Button#setDisabled(Boolp)} 禁用条件回调。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：核验槽位并移除禁用条件。
	 * @param element  目标按钮元素（声明为 Element 以匹配字节码注入签名）
	 * @param original 原始禁用状态提供器
	 * @return 具备局部熔断保护的代理 Boolp
	 */
	public static Boolp wrapButtonDisabled(Element element, Boolp original) {
		if (original == null) return null;
		if (original instanceof WrappedRef wr) {
			UpdateRef ref = wr.getUpdateRef();
			if (ref != null && ref.isRemoved()) return original;
			if (element != null && ref != null) {
				ref.addOnRemove(new RemoveButtonDisabledAction(element, original));
			}
			if (original instanceof WrappedBoolp wb) {
				wb.setFallbackIfAbsent(() -> element instanceof Button b && b.isDisabled());
			}
			return original;
		}
		UpdateRef ref = new UpdateRef(original, null);
		WrappedBoolp wrapper = new WrappedBoolp(ref, () -> element instanceof Button b && b.isDisabled());
		ref.addOnRemove(new RemoveButtonDisabledAction(element, wrapper));
		return wrapper;
	}

	/**
	 * 包装 {@link TextField#setValidator(TextFieldValidator)} 输入验证器。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：核验槽位并移除验证规则。
	 * <p>
	 * <b>关于兜底断言说明：</b><br>
	 * 此处的兜底断言必须固定为 {@code text -> true}，<b>绝对严禁</b>调用宿主元素的 {@code tf.isValid()}。<br>
	 * 这是因为 Arc 的 {@link TextField#isValid()} 实现为 {@code validator == null || validator.valid(text)}，
	 * 会直接向当前安装的 {@code validator} 委托判定。若在兜底逻辑中调用 {@code tf.isValid()}，
	 * 发生异常或 fallback 时将陷入互调死循环（{@code wrapper.valid} &rarr; {@code fallback.valid} &rarr; {@code tf.isValid} &rarr; {@code wrapper.valid}），
	 * 最终触发不可逆的 {@link StackOverflowError}。<br>
	 * 固定兜底为 {@code true} 既斩断了无限递归，又与移除验证器后的默认行为（无限制允许输入）保持语义一致。
	 * </p>
	 * @param element  目标输入框元素（声明为 Element 以匹配字节码注入签名）
	 * @param original 原始输入验证器
	 * @return 具备局部熔断保护的代理 TextFieldValidator
	 */
	public static TextFieldValidator wrapValidator(Element element, TextFieldValidator original) {
		if (original == null) return null;
		if (original instanceof WrappedRef wr) {
			UpdateRef ref = wr.getUpdateRef();
			if (ref != null && ref.isRemoved()) return original;
			if (element != null && ref != null) {
				ref.addOnRemove(new RemoveValidatorAction(element, original));
			}
			if (original instanceof WrappedValidator wv) {
				wv.setFallbackIfAbsent(text -> true);
			}
			return original;
		}
		UpdateRef ref = new UpdateRef(original, null);
		WrappedValidator wrapper = new WrappedValidator(ref, text -> true);
		ref.addOnRemove(new RemoveValidatorAction(element, wrapper));
		return wrapper;
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
			UpdateRef ref = wr.getUpdateRef();
			if (ref != null && ref.isRemoved()) return original;
			if (element != null && ref != null) {
				ref.addOnRemove(new RemoveListenerAction(element, original));
			}
			return original;
		}
		UpdateRef ref = new UpdateRef(original, null);
		WrappedEventListener wrapper = new WrappedEventListener(ref);
		ref.addOnRemove(new RemoveListenerAction(element, wrapper));
		return wrapper;
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
			UpdateRef ref = wr.getUpdateRef();
			if (ref != null && ref.isRemoved()) return original;
			if (element != null && ref != null) {
				ref.addOnRemove(new RemoveCaptureListenerAction(element, original));
			}
			return original;
		}
		UpdateRef ref = new UpdateRef(original, null);
		WrappedEventListener wrapper = new WrappedEventListener(ref);
		ref.addOnRemove(new RemoveCaptureListenerAction(element, wrapper));
		return wrapper;
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
	 * <p>
	 * <b>关于形参说明：</b>保留形参 {@code cell} 是为了保持与字节码注入器（Injector）在拦截 {@code Cell.update(Cons)} 时的调用签名一致；
	 * 由于 Cell 内部机制，发生异常时采用 {@link #NOOP} 静默熔断，不执行外部清理动作，因而无需持有或操作 {@code cell} 引用。
	 * </p>
	 * 发生 {@link LinkageError} 时执行静默熔断（{@link #NOOP}）：由内部引用置空短路阻断高频报错，
	 * 避免调用外部清理误清宿主元素的其他 update 回调。
	 * @param cell     目标表格单元（仅用于注入签名协议兼容，不被持有）
	 * @param original 原始单元更新回调
	 * @return 具备局部熔断保护的代理 Cons
	 */
	public static Cons<?> wrapCellUpdate(Cell<?> cell, Cons<?> original) {
		if (original == null) return null;
		if (original instanceof WrappedRef) {
			return original;
		}
		return new WrappedCons<>(new UpdateRef(original, NOOP));
	}

	/**
	 * 包装 {@link Cell#disabled(Boolf)} 布局单元禁用条件。
	 * 发生 {@link LinkageError} 时执行静默熔断并提供兜底：保持当前禁用状态，
	 * 避免通过外部置空误清底层 update 闭包。
	 * @param cell     目标表格单元
	 * @param original 原始禁用断言
	 * @return 具备局部熔断保护的代理 Boolf
	 */
	public static Boolf<?> wrapCellDisabled(Cell<?> cell, Boolf<?> original) {
		if (original == null) return null;
		Boolf<?> fallback = t -> {
			if (cell == null) return false;
			Element element = cell.get();
			return element instanceof Disableable d && d.isDisabled();
		};
		if (original instanceof WrappedRef) {
			if (original instanceof WrappedBoolf wb) {
				wb.setFallbackIfAbsent(fallback);
			}
			return original;
		}
		return new WrappedBoolf<>(new UpdateRef(original, NOOP), (Boolf) fallback);
	}

	/**
	 * 包装 {@link Cell#tooltip(Cons)} 浮动提示构建回调。
	 * <p>
	 * <b>关于形参与设计说明：</b><br>
	 * 1) 保留形参 {@code cell} 是为了保持与字节码注入器（Injector）在拦截 {@code Cell.tooltip(Cons)} 时的调用签名一致；<br>
	 * 2) 复用静默熔断逻辑，直接构造具备静默保护的代理实例（{@code new WrappedCons<>(new UpdateRef(original, NOOP))}），
	 *    发生 {@link LinkageError} 时仅内部引用置空静默失效，绝不调用 {@code cell.tooltip(null)} 避免重新实例化空 Tooltip 监听器。
	 * </p>
	 * @param cell     目标表格单元（仅用于注入签名协议兼容，不被持有）
	 * @param original 原始提示构建回调
	 * @return 具备局部熔断保护的代理 Cons
	 */
	public static Cons<?> wrapCellTooltip(Cell<?> cell, Cons<?> original) {
		if (original == null) return null;
		if (original instanceof WrappedRef) {
			return original;
		}
		return new WrappedCons<>(new UpdateRef(original, NOOP));
	}

	/**
	 * 包装 {@link Cell#checked(Boolf)} 布局单元选中状态断言。
	 * 发生 {@link LinkageError} 时执行静默熔断并提供兜底：保持当前选中状态，避免误清 update 闭包。
	 * @param cell     目标表格单元
	 * @param original 原始选中断言
	 * @return 具备局部熔断保护的代理 Boolf
	 */
	public static Boolf<?> wrapCellChecked(Cell<?> cell, Boolf<?> original) {
		if (original == null) return null;
		Boolf<?> fallback = t -> {
			if (cell == null) return false;
			Element element = cell.get();
			return element instanceof Button b && b.isChecked();
		};
		if (original instanceof WrappedRef) {
			if (original instanceof WrappedBoolf wb) {
				wb.setFallbackIfAbsent(fallback);
			}
			return original;
		}
		return new WrappedBoolf<>(new UpdateRef(original, NOOP), (Boolf) fallback);
	}

	// 事件静默熔断专用（针对 clicked, hovered 等事件回调，报错仅停止回调，不删元素）

	/**
	 * 包装瞬时交互事件（如 {@code clicked}、{@code hovered}、弹窗生命周期等）。
	 * 形参中的 {@code element} 仅用于保持与字节码注入器（Injector）统一的调用签名协议，
	 * {@code UpdateRef} 内部对其完全忽略且绝不持有任何强引用；
	 * 发生 {@link LinkageError} 时仅清空内部引用静音失效，绝不调用任何外部删除或清理动作，零 Element 引用。
	 * @param element  宿主 Element（仅用于注入签名协议兼容，不被持有）
	 * @param original 原始 Runnable 实例
	 * @return 具备静默熔断保护的代理 Runnable
	 */
	public static Runnable wrapSilent(Element element, Runnable original) {
		return wrap(element, original, NOOP);
	}

	/**
	 * 包装瞬时消费事件回调。
	 * 形参中的 {@code element} 仅用于保持与注入器统一的签名协议，UpdateRef 内部对其忽略且不持有；
	 * 发生 {@link LinkageError} 时仅清空内部引用静音失效，绝不调用外部删除动作。
	 * @param element  宿主 Element（仅用于注入签名协议兼容，不被持有）
	 * @param original 原始 Cons 实例
	 * @return 具备静默熔断保护的代理 Cons
	 */
	public static Cons<?> wrapSilent(Element element, Cons<?> original) {
		return wrap(element, original, NOOP);
	}

	/**
	 * 包装瞬时单浮点手势/滚动回调。
	 * 形参中的 {@code element} 仅用于保持与注入器统一的签名协议，UpdateRef 内部对其忽略且不持有；
	 * 发生 {@link LinkageError} 时仅清空内部引用静音失效，绝不调用外部删除动作。
	 * @param element  宿主 Element（仅用于注入签名协议兼容，不被持有）
	 * @param original 原始 Floatc 实例
	 * @return 具备静默熔断保护的代理 Floatc
	 */
	public static Floatc wrapSilent(Element element, Floatc original) {
		return wrap(element, original, NOOP);
	}

	/**
	 * 包装瞬时双浮点拖拽手势回调。
	 * 形参中的 {@code element} 仅用于保持与注入器统一的签名协议，UpdateRef 内部对其忽略且不持有；
	 * 发生 {@link LinkageError} 时仅清空内部引用静音失效，绝不调用外部删除动作。
	 * @param element  宿主 Element（仅用于注入签名协议兼容，不被持有）
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
	private static volatile boolean hasDeferredRemovals;
	private static volatile boolean flushScheduled;
	private static volatile int flushFailCount;

	private static void deferRemoval(Runnable r) {
		if (r == null || r == NOOP || r == REMOVED) return;
		synchronized (DEFERRED_REMOVALS) {
			if (!DEFERRED_REMOVALS.contains(r, true)) {
				DEFERRED_REMOVALS.add(r);
				hasDeferredRemovals = true;
			}
		}
		tryScheduleDeferredFlush();
	}

	/**
	 * 若当前处于主循环环境（{@code Core.app != null}）且存在暂存的熔断注销动作，
	 * 通过 {@link Core#app} 的 {@link Application#post} 方法向主线程安全点投递清理任务。
	 * 采用 {@code flushScheduled} 标志位与 double-check 快速短路：
	 * 1) 无暂存动作或已有投递任务在等待主线程执行时 100% 零锁竞争与零开销，杜绝重复投递导致的空转 post；
	 * 2) 若投递连续失败达到上限（5次），将暂停在热路径上反复调度，防止异常刷屏。
	 */
	public static void tryScheduleDeferredFlush() {
		if (!hasDeferredRemovals || flushScheduled || Core.app == null || flushFailCount >= 5) return;
		synchronized (DEFERRED_REMOVALS) {
			if (!hasDeferredRemovals || flushScheduled || DEFERRED_REMOVALS.isEmpty() || flushFailCount >= 5) return;
			flushScheduled = true;
		}
		try {
			Core.app.post(UpdateRef::flushDeferredRemovals);
		} catch (Throwable t) {
			synchronized (DEFERRED_REMOVALS) {
				flushScheduled = false;
				flushFailCount++;
				if (flushFailCount == 5) {
					HotSwapAgent.error("[UpdateRef] Core.app.post failed 5 times continuously, suspending retries until new removals added: " + t.getMessage(), t);
				}
			}
		}
	}

	/**
	 * 清理在无主循环环境（如 Core.app == null）下暂存或主线程安全点投递的熔断注销动作。
	 * 在监视器锁外执行回调，彻底消除持有锁调用外部代码与死锁风险。
	 */
	public static void flushDeferredRemovals() {
		Seq<Runnable> toRun;
		synchronized (DEFERRED_REMOVALS) {
			flushScheduled = false;
			hasDeferredRemovals = false;
			flushFailCount = 0;
			if (DEFERRED_REMOVALS.isEmpty()) return;
			toRun = new Seq<>(DEFERRED_REMOVALS);
			DEFERRED_REMOVALS.clear();
		}
		for (int i = 0; i < toRun.size; i++) {
			executeRemove(toRun.get(i));
		}
	}

	/**
	 * 包装 {@link Cons} 或 {@link Runnable} 的事件监听器容器，支持与原始被代理引用的等价比较与注销。
	 */
	public static class EventCons<T> implements Cons<T>, Runnable, WrappedRef {
		public final  UpdateRef ref;
		private final boolean   isRunnable;

		public EventCons(Cons<T> original, Runnable onRemove) {
			this.ref = new UpdateRef(original, onRemove);
			this.isRunnable = false;
		}

		public EventCons(Runnable original, Runnable onRemove) {
			this.ref = new UpdateRef(original, onRemove);
			this.isRunnable = true;
		}

		public EventCons(UpdateRef ref) {
			this.ref = ref;
			this.isRunnable = ref != null && ref.getOriginal() instanceof Runnable;
		}

		@Override
		public void run() {
			if (isRunnable) {
				ref.run();
			} else {
				ref.runCons(null);
			}
		}

		@Override
		public void get(T t) {
			if (isRunnable) {
				ref.run();
			} else {
				ref.runCons(t);
			}
		}

		@Override
		public UpdateRef getUpdateRef() {
			return ref;
		}

		@Override
		public boolean equals(Object o) {
			return WrappedRef.equals(this, o);
		}

		@Override
		public int hashCode() {
			return getOriginalHash();
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
		tryScheduleDeferredFlush();

		if (listener instanceof EventCons ec) {
			if (ec.getUpdateRef() != null && ec.getUpdateRef().isRemoved()) return;
			map.get(type, () -> new Seq<>(Cons.class)).add(listener);
			return;
		}

		if (listener instanceof WrappedRef wr) {
			UpdateRef ref = wr.getUpdateRef();
			if (ref != null && ref.isRemoved()) return;
			// 若已受 UpdateRef 包装，不再重复代理，追加注销动作后直接注入注册表
			if (ref != null) {
				ref.addOnRemove(() -> {
					Seq<Cons<?>> s = map.get(type);
					if (s != null) {
						s.remove(listener, true);
					}
				});
			}
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
	@SuppressWarnings("unchecked")
	public static void eventsRun(Object type, Runnable listener, ObjectMap<Object, Seq<Cons<?>>> map) {
		if (listener == null) return;
		if (map == null) {
			HotSwapAgent.error("[UpdateRef] eventsRun: Events.events registry map is null! Failed to register event listener for: " + type);
			throw new IllegalStateException("[UpdateRef] Events.events registry map is null");
		}
		if (eventsMap != map) eventsMap = map;
		tryScheduleDeferredFlush();

		if (listener instanceof EventCons ec) {
			if (ec.getUpdateRef() != null && ec.getUpdateRef().isRemoved()) return;
			map.get(type, () -> new Seq<>(Cons.class)).add(ec);
			return;
		}

		EventCons<Object>[] box = (EventCons<Object>[]) new EventCons[1];
		Runnable onRemove = () -> {
			if (box[0] != null) {
				Seq<Cons<?>> s = map.get(type);
				if (s != null) {
					s.remove(box[0], true);
				}
			}
		};

		EventCons<Object> wrapper;
		if (listener instanceof WrappedRef wr) {
			UpdateRef ref = wr.getUpdateRef();
			if (ref != null && ref.isRemoved()) return;
			// 复用已有的 UpdateRef，追加从注册表注销的动作，避免多层嵌套代理吞掉 LinkageError
			if (ref != null) {
				ref.addOnRemove(onRemove);
			}
			wrapper = new EventCons<>(ref);
		} else {
			wrapper = new EventCons<>(listener, onRemove);
		}
		box[0] = wrapper;
		map.get(type, () -> new Seq<>(Cons.class)).add(wrapper);
	}

	private static Object unwrapTargetKey(Object obj) {
		if (obj instanceof WrappedRef wr) return wr.getOriginal();
		return obj;
	}

	private static boolean matchListener(Cons<?> item, Object listener, Object targetKey) {
		if (item == listener || (targetKey != null && item == targetKey)) return true;
		Object itemKey = unwrapTargetKey(item);
		return targetKey != null && itemKey != null && Objects.equals(itemKey, targetKey);
	}

	/**
	 * 代理 {@link Events#remove(Class, Cons)}，支持解包匹配并注销已被包装的事件监听器。
	 * @param type     事件类型 Class
	 * @param listener 待注销的监听器（可以是原始 listener，也可以是 EventCons 或 WrappedRef 代理实例）
	 * @param map      Events 内部私有事件注册表（100% 零反射原生自举传入）
	 * @param <T>      事件类型
	 * @return 若成功找到并注销返回 true，否则返回 false
	 */
	public static <T> boolean eventsRemove(Class<T> type, Cons<T> listener, ObjectMap<Object, Seq<Cons<?>>> map) {
		if (listener == null) return false;
		if (map != null && eventsMap != map) eventsMap = map;
		if (map == null) return false;
		tryScheduleDeferredFlush();
		Seq<Cons<?>> seq = map.get(type);
		if (seq == null) return false;

		Object targetKey = unwrapTargetKey(listener);
		for (int i = 0; i < seq.size; i++) {
			if (matchListener(seq.items[i], listener, targetKey)) {
				seq.remove(i);
				return true;
			}
		}
		return false;
	}

	/**
	 * 注销通过 {@link Events#run(Object, Runnable)} 注册的监听器。
	 * @param type     事件类型
	 * @param listener 原始 Runnable 实例或包装代理实例
	 * @return 若成功找到并注销返回 true，否则返回 false
	 */
	public static boolean removeEventRun(Object type, Runnable listener) {
		if (listener == null) return false;
		ObjectMap<Object, Seq<Cons<?>>> map = eventsMap;
		if (map == null) return false;
		tryScheduleDeferredFlush();
		Seq<Cons<?>> seq = map.get(type);
		if (seq == null) return false;

		Object targetKey = unwrapTargetKey(listener);
		for (int i = 0; i < seq.size; i++) {
			if (matchListener(seq.items[i], listener, targetKey)) {
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

	/** 清空被代理的原始函数引用，切断死代码调用并释放闭包引用供 GC 回收 */
	private void clearFn() {
		this.fn = null;
		this.original = null;
	}

	/**
	 * 执行被代理的 {@link Prov}。捕获 {@link LinkageError} 并转入熔断处理，熔断后返回 null。
	 */
	public <T> T runProv() {
		return runProv(null);
	}

	/**
	 * 执行被代理的 {@link Prov}。捕获 {@link LinkageError} 并转入熔断处理，熔断后执行兜底逻辑。
	 * @param fallback 熔断发生或引用清空时的兜底提供器
	 * @param <T>      返回值类型
	 * @return 运行结果或兜底结果
	 */
	@SuppressWarnings("unchecked")
	public <T> T runProv(Prov<T> fallback) {
		var f = (Prov<T>) this.fn;
		if (checkFn(f)) return fallback != null ? fallback.get() : null;
		try {
			return f.get();
		} catch (LinkageError e) {
			onLinkageError(f, e);
			return fallback != null ? fallback.get() : null;
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
	 * 若根本原因链包含 {@link ExceptionInInitializerError}（例如类首次初始化失败后再次调用引发的 NoClassDefFoundError），同样认定为业务异常，不予熔断。
	 * @param t 目标异常
	 * @return 若为热重载引起的结构缺失异常则返回 true
	 */
	public static boolean isHotSwapLinkageError(Throwable t) {
		if (t == null) return false;
		Throwable cur = t;
		int depth = 0;
		while (cur != null && depth++ < 10) {
			if (cur instanceof ExceptionInInitializerError) {
				return false;
			}
			cur = cur.getCause();
		}
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