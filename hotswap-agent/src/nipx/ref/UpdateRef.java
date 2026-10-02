package nipx.ref;

import arc.Core;
import arc.func.*;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.event.EventListener;
import arc.scene.ui.Button;
import arc.scene.ui.TextField;
import arc.scene.ui.TextField.TextFieldValidator;
import arc.scene.ui.layout.Cell;
import nipx.HotSwapAgent;

import java.util.*;

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
 *   <li><b>无 Element 强绑定：</b>类内部仅维护 {@code fn} 与 {@code onRemove} 两个轻量引用，不直接持有 UI 节点，
 *       生命周期结束后即刻切断闭包引用，对 GC 极度友好。</li>
 *   <li><b>静默降级（Silent）：</b>对于点击、鼠标悬停、弹窗生命周期等瞬时事件，采用 {@link #wrapSilent}，异常时仅将内部引用置空静音。</li>
 *   <li><b>与 {@link nipx.LambdaAligner} 的双轨协同：</b><br>
 *       对于已被删除的“孤儿方法”，{@code LambdaAligner} 内部通过 {@link StackWalker} 探测调用栈：
 *       若检测到当前由 {@link UpdateRef} 调用，则定向抛出 {@link NoSuchMethodError}，精准触发此处的局部熔断与清理动作；
 *       若为普通业务代码调用，则静默返回类型默认值，绝不中断业务。</li>
 * </ul>
 *
 * @see nipx.LambdaAligner
 * @see nipx.LambdaRef
 * @see nipx.Injector
 */
public class UpdateRef {

	/** 空操作常量，用于事件回调发生异常时的静默熔断，防止触发任何外部破坏性清理 */
	public static final Runnable NOOP = () -> {};

	/** 被代理的目标函数式接口实例（例如 {@link Runnable}、{@link Cons} 等）；发生异常或注销后置为 null */
	private volatile Object   fn;
	/** 自定义熔断/销毁动作；为 null 时表示静默失效（仅清除 fn 引用，不再重复执行） */
	private volatile Runnable onRemove;

	/** 线程本地上下文，支持通过 {@link #withOnRemove} 跨调用栈隐式传递熔断清理回调 */
	private static final ThreadLocal<Runnable> CONTEXT_ON_REMOVE = new ThreadLocal<>();

	/**
	 * 私有构造方法，初始化包装引用与熔断动作。
	 *
	 * @param fn       原始函数式接口实例
	 * @param onRemove 显式指定的清理动作；若为 null，则尝试从当前线程上下文 {@link #CONTEXT_ON_REMOVE} 继承
	 */
	private UpdateRef(Object fn, Runnable onRemove) {
		this.fn = fn;
		this.onRemove = onRemove != null ? onRemove : CONTEXT_ON_REMOVE.get();
	}

	/**
	 * 获取当前配置的熔断清理动作。
	 *
	 * @return 当前绑定的清理动作，可能为 null
	 */
	public Runnable getOnRemove() {
		return onRemove;
	}

	/**
	 * 动态设置或替换当前包装引用的熔断清理动作。
	 *
	 * @param onRemove 新的清理动作
	 */
	public void setOnRemove(Runnable onRemove) {
		this.onRemove = onRemove;
	}

	/**
	 * 在指定代码块的作用域内设置默认的 {@code onRemove} 动作。
	 * 该作用域内由 {@link UpdateRef} 创建且未显式指定清理动作的包装实例，将自动继承此动作。
	 *
	 * @param onRemoveAction 该作用域内默认的清理动作
	 * @param block          受该作用域保护并执行的代码块
	 */
	public static void withOnRemove(Runnable onRemoveAction, Runnable block) {
		Runnable old = CONTEXT_ON_REMOVE.get();
		CONTEXT_ON_REMOVE.set(onRemoveAction);
		try {
			block.run();
		} finally {
			CONTEXT_ON_REMOVE.set(old);
		}
	}

	/**
	 * 在指定代码块的作用域内设置默认的 {@code onRemove} 动作并返回执行结果。
	 *
	 * @param onRemoveAction 该作用域内默认的清理动作
	 * @param block          受该作用域保护并提供返回值的代码块
	 * @param <T>            返回值类型
	 * @return 代码块的执行返回值
	 */
	public static <T> T withOnRemove(Runnable onRemoveAction, Prov<T> block) {
		Runnable old = CONTEXT_ON_REMOVE.get();
		CONTEXT_ON_REMOVE.set(onRemoveAction);
		try {
			return block.get();
		} finally {
			CONTEXT_ON_REMOVE.set(old);
		}
	}

	//region 通用 wrap 重载

	//region 通用 wrap 重载（不依赖 Element）

	/**
	 * 将原始 {@link Runnable} 包装为具备热重载容错保护的代理，异常时静默失效。
	 *
	 * @param original 原始 Runnable 实例
	 * @return 具备容错保护的代理 Runnable
	 */
	public static Runnable wrap(Runnable original) {
		return wrap(original, (Runnable) null);
	}

	/**
	 * 将原始 {@link Runnable} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 *
	 * @param original 原始 Runnable 实例
	 * @param onRemove 发生 LinkageError 时的清理/注销动作
	 * @return 具备容错保护的代理 Runnable
	 */
	public static Runnable wrap(Runnable original, Runnable onRemove) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, onRemove)::run;
	}

	/**
	 * 将原始 {@link Prov} 包装为具备热重载容错保护的代理，异常时返回 null 并静默失效。
	 *
	 * @param original 原始 Prov 实例
	 * @param <T>      提供的值类型
	 * @return 具备容错保护的代理 Prov
	 */
	public static <T> Prov<T> wrap(Prov<T> original) {
		return wrap(original, (Runnable) null);
	}

	/**
	 * 将原始 {@link Prov} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 *
	 * @param original 原始 Prov 实例
	 * @param onRemove 发生 LinkageError 时的清理/注销动作
	 * @param <T>      提供的值类型
	 * @return 具备容错保护的代理 Prov
	 */
	public static <T> Prov<T> wrap(Prov<T> original, Runnable onRemove) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, onRemove)::runProv;
	}

	/**
	 * 将原始 {@link Boolp} 包装为具备热重载容错保护的代理，异常时返回 false 并静默失效。
	 *
	 * @param original 原始 Boolp 实例
	 * @return 具备容错保护的代理 Boolp
	 */
	public static Boolp wrap(Boolp original) {
		return wrap(original, (Runnable) null);
	}

	/**
	 * 将原始 {@link Boolp} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 *
	 * @param original 原始 Boolp 实例
	 * @param onRemove 发生 LinkageError 时的清理/注销动作
	 * @return 具备容错保护的代理 Boolp
	 */
	public static Boolp wrap(Boolp original, Runnable onRemove) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, onRemove)::runBoolp;
	}

	/**
	 * 将原始 {@link Cons} 包装为具备热重载容错保护的代理，异常时静默失效。
	 *
	 * @param original 原始 Cons 实例
	 * @param <T>      消费的值类型
	 * @return 具备容错保护的代理 Cons
	 */
	public static <T> Cons<T> wrap(Cons<T> original) {
		return wrap(original, (Runnable) null);
	}

	/**
	 * 将原始 {@link Cons} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 *
	 * @param original 原始 Cons 实例
	 * @param onRemove 发生 LinkageError 时的清理/注销动作
	 * @param <T>      消费的值类型
	 * @return 具备容错保护的代理 Cons
	 */
	public static <T> Cons<T> wrap(Cons<T> original, Runnable onRemove) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, onRemove)::runCons;
	}
	//endregion

	//region 兼容 Element 的通用 wrap 重载

	/**
	 * 将原始 {@link Runnable} 包装为具备热重载容错保护的代理（兼容原有 Element 接口）。
	 *
	 * @param element  宿主 Element（仅作兼容参数，UpdateRef 不再强持有该引用）
	 * @param original 原始 Runnable 实例
	 * @return 具备容错保护的代理 Runnable
	 */
	public static Runnable wrap(Element element, Runnable original) {
		return wrap(element, original, null);
	}

	/**
	 * 将原始 {@link Runnable} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 *
	 * @param element  宿主 Element
	 * @param original 原始 Runnable 实例
	 * @param onRemove 发生 LinkageError 时的清理/注销动作
	 * @return 具备容错保护的代理 Runnable
	 */
	public static Runnable wrap(Element element, Runnable original, Runnable onRemove) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, onRemove)::run;
	}

	/**
	 * 将原始 {@link Prov} 包装为具备热重载容错保护的代理（兼容原有 Element 接口）。
	 *
	 * @param element  宿主 Element
	 * @param original 原始 Prov 实例
	 * @return 具备容错保护的代理 Prov
	 */
	public static Prov<?> wrap(Element element, Prov<?> original) {
		return wrap(element, original, null);
	}

	/**
	 * 将原始 {@link Prov} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 *
	 * @param element  宿主 Element
	 * @param original 原始 Prov 实例
	 * @param onRemove 发生 LinkageError 时的清理/注销动作
	 * @return 具备容错保护的代理 Prov
	 */
	public static Prov<?> wrap(Element element, Prov<?> original, Runnable onRemove) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, onRemove)::runProv;
	}

	/**
	 * 将原始 {@link Boolp} 包装为具备热重载容错保护的代理（兼容原有 Element 接口）。
	 *
	 * @param element  宿主 Element
	 * @param original 原始 Boolp 实例
	 * @return 具备容错保护的代理 Boolp
	 */
	public static Boolp wrap(Element element, Boolp original) {
		return wrap(element, original, null);
	}

	/**
	 * 将原始 {@link Boolp} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 *
	 * @param element  宿主 Element
	 * @param original 原始 Boolp 实例
	 * @param onRemove 发生 LinkageError 时的清理/注销动作
	 * @return 具备容错保护的代理 Boolp
	 */
	public static Boolp wrap(Element element, Boolp original, Runnable onRemove) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, onRemove)::runBoolp;
	}

	/**
	 * 将原始 {@link Cons} 包装为具备热重载容错保护的代理（兼容原有 Element 接口）。
	 *
	 * @param element  宿主 Element
	 * @param original 原始 Cons 实例
	 * @return 具备容错保护的代理 Cons
	 */
	public static Cons<?> wrap(Element element, Cons<?> original) {
		return wrap(element, original, null);
	}

	/**
	 * 将原始 {@link Cons} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 *
	 * @param element  宿主 Element
	 * @param original 原始 Cons 实例
	 * @param onRemove 发生 LinkageError 时的清理/注销动作
	 * @return 具备容错保护的代理 Cons
	 */
	public static Cons<?> wrap(Element element, Cons<?> original, Runnable onRemove) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, onRemove)::runCons;
	}

	/**
	 * 将原始 {@link Boolf} 包装为具备热重载容错保护的代理（兼容原有 Element 接口）。
	 *
	 * @param element  宿主 Element
	 * @param original 原始 Boolf 实例
	 * @return 具备容错保护的代理 Boolf
	 */
	public static Boolf<?> wrap(Element element, Boolf<?> original) {
		return wrap(element, original, null);
	}

	/**
	 * 将原始 {@link Boolf} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 *
	 * @param element  宿主 Element
	 * @param original 原始 Boolf 实例
	 * @param onRemove 发生 LinkageError 时的清理/注销动作
	 * @return 具备容错保护的代理 Boolf
	 */
	public static Boolf<?> wrap(Element element, Boolf<?> original, Runnable onRemove) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, onRemove)::runBoolf;
	}

	/**
	 * 将文本框验证器 {@link TextFieldValidator} 包装为具备热重载容错保护的代理。
	 *
	 * @param element  宿主 Element
	 * @param original 原始验证器
	 * @return 具备容错保护的代理验证器
	 */
	public static TextFieldValidator wrap(Element element, TextFieldValidator original) {
		return wrap(element, original, null);
	}

	/**
	 * 将文本框验证器 {@link TextFieldValidator} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 *
	 * @param element  宿主 Element
	 * @param original 原始验证器
	 * @param onRemove 发生 LinkageError 时的清理动作
	 * @return 具备容错保护的代理验证器
	 */
	public static TextFieldValidator wrap(Element element, TextFieldValidator original, Runnable onRemove) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, onRemove)::runValidator;
	}

	/**
	 * 将原始单浮点消费回调 {@link Floatc} 包装为具备热重载容错保护的代理。
	 *
	 * @param element  宿主 Element
	 * @param original 原始 Floatc 实例
	 * @return 具备容错保护的代理 Floatc
	 */
	public static Floatc wrap(Element element, Floatc original) {
		return wrap(element, original, null);
	}

	/**
	 * 将原始单浮点消费回调 {@link Floatc} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 *
	 * @param element  宿主 Element
	 * @param original 原始 Floatc 实例
	 * @param onRemove 发生 LinkageError 时的清理动作
	 * @return 具备容错保护的代理 Floatc
	 */
	public static Floatc wrap(Element element, Floatc original, Runnable onRemove) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, onRemove)::runFloatc;
	}

	/**
	 * 将原始双浮点消费回调 {@link Floatc2} 包装为具备热重载容错保护的代理。
	 *
	 * @param element  宿主 Element
	 * @param original 原始 Floatc2 实例
	 * @return 具备容错保护的代理 Floatc2
	 */
	public static Floatc2 wrap(Element element, Floatc2 original) {
		return wrap(element, original, null);
	}

	/**
	 * 将原始双浮点消费回调 {@link Floatc2} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 *
	 * @param element  宿主 Element
	 * @param original 原始 Floatc2 实例
	 * @param onRemove 发生 LinkageError 时的清理动作
	 * @return 具备容错保护的代理 Floatc2
	 */
	public static Floatc2 wrap(Element element, Floatc2 original, Runnable onRemove) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, onRemove)::runFloatc2;
	}

	/**
	 * 将事件监听器 {@link EventListener} 包装为具备热重载容错保护的代理。
	 * 若未指定 onRemove，默认在发生异常时精准注销该监听器（{@code element.removeListener}）。
	 *
	 * @param element  宿主 Element
	 * @param original 原始监听器
	 * @return 具备容错保护的代理监听器
	 */
	public static EventListener wrap(Element element, EventListener original) {
		return wrap(element, original, null);
	}

	/**
	 * 将事件监听器 {@link EventListener} 包装为具备热重载容错保护的代理，并在异常时触发指定熔断动作。
	 *
	 * @param element  宿主 Element
	 * @param original 原始监听器
	 * @param onRemove 自定义熔断动作；若为 null，默认精准注销该监听器
	 * @return 具备容错保护的代理监听器
	 */
	public static EventListener wrap(Element element, EventListener original, Runnable onRemove) {
		if (returnOriginal(original)) return original;
		UpdateRef ref = new UpdateRef(original, onRemove);
		EventListener listener = ref::runEventListener;
		if (ref.onRemove == null && element != null) {
			ref.onRemove = () -> element.removeListener(listener);
		}
		return listener;
	}

	//endregion

	//region 场景专用 wrap 方法（精准局部熔断）

	/**
	 * 包装 {@link Element#update(Runnable)} 回调。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：{@code element.update(null)}，仅移除该每帧更新回调，绝不删除 Element。
	 *
	 * @param element  目标 UI 节点
	 * @param original 原始更新回调
	 * @return 具备局部熔断保护的代理 Runnable
	 */
	public static Runnable wrapUpdate(Element element, Runnable original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, () -> {
			if (element != null) element.update(null);
		})::run;
	}

	/**
	 * 包装 {@link Element#visible(Boolp)} 条件回调。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：{@code element.visible(null)}，仅移除可见性条件。
	 *
	 * @param element  目标 UI 节点
	 * @param original 原始可见性提供器
	 * @return 具备局部熔断保护的代理 Boolp
	 */
	public static Boolp wrapVisible(Element element, Boolp original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, () -> {
			if (element != null) element.visible(null);
		})::runBoolp;
	}

	/**
	 * 包装 {@link Element#touchable(Prov)} 条件回调。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：{@code element.touchable((Prov) null)}，仅移除可触摸状态动态提供器。
	 *
	 * @param element  目标 UI 节点
	 * @param original 原始 Touchable 提供器
	 * @return 具备局部熔断保护的代理 Prov
	 */
	public static Prov<?> wrapTouchable(Element element, Prov<?> original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, () -> {
			if (element != null) element.touchable((Prov) null);
		})::runProv;
	}

	/**
	 * 包装 {@link Button#setDisabled(Boolp)} 禁用条件回调。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：{@code button.setDisabled(null)}，仅移除禁用条件。
	 *
	 * @param element  目标按钮元素
	 * @param original 原始禁用状态提供器
	 * @return 具备局部熔断保护的代理 Boolp
	 */
	public static Boolp wrapButtonDisabled(Element element, Boolp original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, () -> {
			if (element instanceof Button b) b.setDisabled(null);
		})::runBoolp;
	}

	/**
	 * 包装 {@link TextField#setValidator(TextFieldValidator)} 输入验证器。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：{@code textField.setValidator(null)}，仅移除验证规则。
	 *
	 * @param element  目标输入框元素
	 * @param original 原始输入验证器
	 * @return 具备局部熔断保护的代理 TextFieldValidator
	 */
	public static TextFieldValidator wrapValidator(Element element, TextFieldValidator original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, () -> {
			if (element instanceof TextField tf) tf.setValidator(null);
		})::runValidator;
	}

	/**
	 * 包装 {@link Element#addListener(EventListener)} 事件监听器。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：{@code element.removeListener(ref)}，仅注销当前出故障的监听器。
	 *
	 * @param element  目标 UI 节点
	 * @param original 原始监听器
	 * @return 具备局部熔断保护的代理 EventListener
	 */
	public static EventListener wrapListener(Element element, EventListener original) {
		if (returnOriginal(original)) return original;
		UpdateRef ref = new UpdateRef(original, null);
		EventListener listener = ref::runEventListener;
		ref.setOnRemove(() -> {
			if (element != null) element.removeListener(listener);
		});
		return listener;
	}

	/**
	 * 包装 {@link Element#addCaptureListener(EventListener)} 捕获阶段监听器。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：{@code element.removeCaptureListener(ref)}，仅注销当前捕获监听器。
	 *
	 * @param element  目标 UI 节点
	 * @param original 原始监听器
	 * @return 具备局部熔断保护的代理 EventListener
	 */
	public static EventListener wrapCaptureListener(Element element, EventListener original) {
		if (returnOriginal(original)) return original;
		UpdateRef ref = new UpdateRef(original, null);
		EventListener listener = ref::runEventListener;
		ref.setOnRemove(() -> {
			if (element != null) element.removeCaptureListener(listener);
		});
		return listener;
	}

	// Cell 专用

	/**
	 * 包装 {@link Cell#update(Cons)} 布局单元每帧消费回调。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：{@code cell.update(null)}。
	 *
	 * @param cell     目标表格单元
	 * @param original 原始单元更新回调
	 * @return 具备局部熔断保护的代理 Cons
	 */
	public static Cons<?> wrapCellUpdate(Cell<?> cell, Cons<?> original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, () -> {
			if (cell != null) cell.update(null);
		})::runCons;
	}

	/**
	 * 包装 {@link Cell#disabled(Boolf)} 布局单元禁用条件。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：{@code cell.disabled(null)}。
	 *
	 * @param cell     目标表格单元
	 * @param original 原始禁用断言
	 * @return 具备局部熔断保护的代理 Boolf
	 */
	public static Boolf<?> wrapCellDisabled(Cell<?> cell, Boolf<?> original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, () -> {
			if (cell != null) cell.disabled(null);
		})::runBoolf;
	}

	/**
	 * 包装 {@link Cell#tooltip(Cons)} 浮动提示构建回调。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：{@code cell.tooltip((Cons) null)}。
	 *
	 * @param cell     目标表格单元
	 * @param original 原始提示构建回调
	 * @return 具备局部熔断保护的代理 Cons
	 */
	public static Cons<?> wrapCellTooltip(Cell<?> cell, Cons<?> original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, () -> {
			if (cell != null) cell.tooltip((Cons) null);
		})::runCons;
	}

	/**
	 * 包装 {@link Cell#checked(Boolf)} 布局单元选中状态断言。
	 * 发生 {@link LinkageError} 时执行精准局部熔断：{@code cell.checked(null)}。
	 *
	 * @param cell     目标表格单元
	 * @param original 原始选中断言
	 * @return 具备局部熔断保护的代理 Boolf
	 */
	public static Boolf<?> wrapCellChecked(Cell<?> cell, Boolf<?> original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, () -> {
			if (cell != null) cell.checked(null);
		})::runBoolf;
	}

	// 事件静默熔断专用（针对 clicked, hovered 等事件回调，报错仅停止回调，不删元素）

	/**
	 * 包装瞬时交互事件（如 {@code clicked}、{@code hovered}、弹窗生命周期等）。
	 * 发生 {@link LinkageError} 时仅清空内部引用静音失效，绝不调用任何外部删除动作，零 Element 引用。
	 *
	 * @param element  宿主 Element
	 * @param original 原始 Runnable 实例
	 * @return 具备静默熔断保护的代理 Runnable
	 */
	public static Runnable wrapSilent(Element element, Runnable original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, NOOP)::run;
	}

	/**
	 * 包装瞬时消费事件回调。发生 {@link LinkageError} 时仅清空内部引用静音失效。
	 *
	 * @param element  宿主 Element
	 * @param original 原始 Cons 实例
	 * @return 具备静默熔断保护的代理 Cons
	 */
	public static Cons<?> wrapSilent(Element element, Cons<?> original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, NOOP)::runCons;
	}

	/**
	 * 包装瞬时单浮点手势/滚动回调。发生 {@link LinkageError} 时仅清空内部引用静音失效。
	 *
	 * @param element  宿主 Element
	 * @param original 原始 Floatc 实例
	 * @return 具备静默熔断保护的代理 Floatc
	 */
	public static Floatc wrapSilent(Element element, Floatc original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, NOOP)::runFloatc;
	}

	/**
	 * 包装瞬时双浮点拖拽手势回调。发生 {@link LinkageError} 时仅清空内部引用静音失效。
	 *
	 * @param element  宿主 Element
	 * @param original 原始 Floatc2 实例
	 * @return 具备静默熔断保护的代理 Floatc2
	 */
	public static Floatc2 wrapSilent(Element element, Floatc2 original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, NOOP)::runFloatc2;
	}

	//endregion

	private static boolean returnOriginal(Object original) {
		if (original == null) return true;
		if (original.getClass().getName().startsWith(UpdateRef.class.getName())) return true;

		return false;
	}

	/**
	 * 检查原始函数引用是否已被清空。
	 * 若为 null，说明已发生热重载异常或已被清理，异步触发熔断动作并返回 true 以便短路跳过执行。
	 *
	 * @param f 目标函数对象
	 * @return true 表示已被清空，当前执行应短路中断
	 */
	private boolean checkFn(Object f) {
		if (f == null) {
			// fn 已被清空（HotSwap 删除或 NoSuchMethodError 兜底），触发熔断动作
			Core.app.post(this::doRemove);
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
			onNoSuchMethodError(f, e);
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
			onNoSuchMethodError(f, e);
			return null;
		}
	}

	/**
	 * 执行被代理的 {@link Boolp}。捕获 {@link LinkageError} 并转入熔断处理，熔断后返回 false。
	 */
	public boolean runBoolp() {
		var f = (Boolp) this.fn;
		if (checkFn(f)) return false;
		try {
			return f.get();
		} catch (LinkageError e) {
			onNoSuchMethodError(f, e);
			return false;
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
			onNoSuchMethodError(f, e);
		}
	}

	/**
	 * 执行被代理的 {@link Boolf}。捕获 {@link LinkageError} 并转入熔断处理，熔断后返回 false。
	 */
	@SuppressWarnings("unchecked")
	public <T> boolean runBoolf(T t) {
		var f = (Boolf<T>) this.fn;
		if (checkFn(f)) return false;
		try {
			return f.get(t);
		} catch (LinkageError e) {
			onNoSuchMethodError(f, e);
			return false;
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
			onNoSuchMethodError(fn, e);
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
			onNoSuchMethodError(fn, e);
		}
	}

	/**
	 * 执行被代理的 {@link TextFieldValidator}。捕获 {@link LinkageError} 并转入熔断处理，熔断后返回 false。
	 */
	public boolean runValidator(String t) {
		var f = (TextFieldValidator) this.fn;
		if (checkFn(f)) return false;
		try {
			return f.valid(t);
		} catch (LinkageError e) {
			onNoSuchMethodError(f, e);
			return false;
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
			onNoSuchMethodError(f, e);
			return false;
		}
	}

	/**
	 * 执行熔断清理动作。
	 * 保证有且仅有一次执行，并在执行时即刻将 {@link #onRemove} 置空以彻底释放所捕获的变量闭包。
	 */
	private void doRemove() {
		Runnable r = this.onRemove;
		this.onRemove = null; // 确保仅执行一次，且断开对捕获对象的引用
		if (r != null) {
			try {
				r.run();
			} catch (Throwable t) {
				HotSwapAgent.error("[UpdateRef] onRemove failed: " + t.getMessage(), t);
			}
		}
	}

	/**
	 * 处理 LinkageError（热重载导致方法不存在或签名不兼容）：
	 * 1) 打印诊断日志方便热重载排查；
	 * 2) 立即置空 {@code fn} 停止后续调用；
	 * 3) 投递主线程异步任务执行精准熔断清理 {@link #doRemove()}。
	 *
	 * @param f 发生故障的原始函数实例
	 * @param e 捕获的链接错误异常
	 */
	private void onNoSuchMethodError(Object f, LinkageError e) {
		HotSwapAgent.info("[UpdateRef] NoSuchMethodError from " + (f == null ? "?" : f.getClass().getName())
		                  + ": " + e.getMessage());
		clearFn();
		Core.app.post(this::doRemove);
	}

}