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

/** @see nipx.LambdaRef */
public class UpdateRef {

	public static final Runnable NOOP = () -> {};

	private volatile Object   fn;
	/** 直接强引用即可：element -> listener -> UpdateRef -> element 只是孤立的环，不影响 GC */
	private final    Element  element;
	/** 自定义销毁动作；为 null 时默认回退到 Core.app.post(element::remove) 或监听器注销 */
	private volatile Runnable onRemove;

	private static final ThreadLocal<Runnable> CONTEXT_ON_REMOVE = new ThreadLocal<>();

	private UpdateRef(Object fn, Element element, Runnable onRemove) {
		this.fn = fn;
		this.element = element;
		this.onRemove = onRemove != null ? onRemove : CONTEXT_ON_REMOVE.get();
	}

	public Runnable getOnRemove() {
		return onRemove;
	}

	public void setOnRemove(Runnable onRemove) {
		this.onRemove = onRemove;
	}

	public Element getElement() {
		return element;
	}

	/**
	 * 在指定代码块内设置默认的 onRemove 动作，该作用域内创建的 UpdateRef 将自动继承此动作。
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

	public static Runnable wrap(Element element, Runnable original) {
		return wrap(element, original, null);
	}

	public static Runnable wrap(Element element, Runnable original, Runnable onRemove) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element, onRemove)::run;
	}

	public static Prov<?> wrap(Element element, Prov<?> original) {
		return wrap(element, original, null);
	}

	public static Prov<?> wrap(Element element, Prov<?> original, Runnable onRemove) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element, onRemove)::runProv;
	}

	public static Boolp wrap(Element element, Boolp original) {
		return wrap(element, original, null);
	}

	public static Boolp wrap(Element element, Boolp original, Runnable onRemove) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element, onRemove)::runBoolp;
	}

	public static Cons<?> wrap(Element element, Cons<?> original) {
		return wrap(element, original, null);
	}

	public static Cons<?> wrap(Element element, Cons<?> original, Runnable onRemove) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element, onRemove)::runCons;
	}

	public static Boolf<?> wrap(Element element, Boolf<?> original) {
		return wrap(element, original, null);
	}

	public static Boolf<?> wrap(Element element, Boolf<?> original, Runnable onRemove) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element, onRemove)::runBoolf;
	}

	public static TextFieldValidator wrap(Element element, TextFieldValidator original) {
		return wrap(element, original, null);
	}

	public static TextFieldValidator wrap(Element element, TextFieldValidator original, Runnable onRemove) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element, onRemove)::runValidator;
	}

	public static Floatc wrap(Element element, Floatc original) {
		return wrap(element, original, null);
	}

	public static Floatc wrap(Element element, Floatc original, Runnable onRemove) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element, onRemove)::runFloatc;
	}

	public static Floatc2 wrap(Element element, Floatc2 original) {
		return wrap(element, original, null);
	}

	public static Floatc2 wrap(Element element, Floatc2 original, Runnable onRemove) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element, onRemove)::runFloatc2;
	}

	public static EventListener wrap(Element element, EventListener original) {
		return wrap(element, original, null);
	}

	public static EventListener wrap(Element element, EventListener original, Runnable onRemove) {
		if (returnOriginal(original)) return original;
		UpdateRef ref = new UpdateRef(original, element, onRemove);
		EventListener listener = ref::runEventListener;
		if (ref.onRemove == null && element != null) {
			ref.onRemove = () -> element.removeListener(listener);
		}
		return listener;
	}

	//endregion

	//region 场景专用 wrap 方法（精准局部熔断）

	public static Runnable wrapUpdate(Element element, Runnable original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element, () -> {
			if (element != null) element.update(null);
		})::run;
	}

	public static Boolp wrapVisible(Element element, Boolp original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element, () -> {
			if (element != null) element.visible(null);
		})::runBoolp;
	}

	public static Prov<?> wrapTouchable(Element element, Prov<?> original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element, () -> {
			if (element != null) element.touchable((Prov) null);
		})::runProv;
	}

	public static Boolp wrapButtonDisabled(Element element, Boolp original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element, () -> {
			if (element instanceof Button b) b.setDisabled(null);
		})::runBoolp;
	}

	public static TextFieldValidator wrapValidator(Element element, TextFieldValidator original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element, () -> {
			if (element instanceof TextField tf) tf.setValidator(null);
		})::runValidator;
	}

	public static EventListener wrapListener(Element element, EventListener original) {
		if (returnOriginal(original)) return original;
		UpdateRef ref = new UpdateRef(original, element, null);
		EventListener listener = ref::runEventListener;
		ref.setOnRemove(() -> {
			if (element != null) element.removeListener(listener);
		});
		return listener;
	}

	public static EventListener wrapCaptureListener(Element element, EventListener original) {
		if (returnOriginal(original)) return original;
		UpdateRef ref = new UpdateRef(original, element, null);
		EventListener listener = ref::runEventListener;
		ref.setOnRemove(() -> {
			if (element != null) element.removeCaptureListener(listener);
		});
		return listener;
	}

	// Cell 专用
	public static Cons<?> wrapCellUpdate(Cell<?> cell, Cons<?> original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, cell != null ? cell.get() : null, () -> {
			if (cell != null) cell.update(null);
		})::runCons;
	}

	public static Boolf<?> wrapCellDisabled(Cell<?> cell, Boolf<?> original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, cell != null ? cell.get() : null, () -> {
			if (cell != null) cell.disabled(null);
		})::runBoolf;
	}

	public static Cons<?> wrapCellTooltip(Cell<?> cell, Cons<?> original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, cell != null ? cell.get() : null, () -> {
			if (cell != null) cell.tooltip((Cons) null);
		})::runCons;
	}

	public static Boolf<?> wrapCellChecked(Cell<?> cell, Boolf<?> original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, cell != null ? cell.get() : null, () -> {
			if (cell != null) cell.checked(null);
		})::runBoolf;
	}

	// 事件静默熔断专用（针对 clicked, hovered 等事件回调，报错仅停止回调，不删元素）
	public static Runnable wrapSilent(Element element, Runnable original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element, NOOP)::run;
	}

	public static Cons<?> wrapSilent(Element element, Cons<?> original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element, NOOP)::runCons;
	}

	public static Floatc wrapSilent(Element element, Floatc original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element, NOOP)::runFloatc;
	}

	public static Floatc2 wrapSilent(Element element, Floatc2 original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element, NOOP)::runFloatc2;
	}

	//endregion

	private static boolean returnOriginal(Object original) {
		if (original == null) return true;
		if (original.getClass().getName().startsWith(UpdateRef.class.getName())) return true;

		return false;
	}

	public void run() {
		var f = (Runnable) this.fn;
		if (checkFn(f)) return;
		try {
			f.run();
		} catch (LinkageError e) {
			onNoSuchMethodError(f, e);
		}
	}

	private void clearFn() {
		this.fn = null;
	}

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

	public void runFloatc(float f) {
		var fn = (Floatc) this.fn;
		if (checkFn(fn)) return;
		try {
			fn.get(f);
		} catch (LinkageError e) {
			onNoSuchMethodError(fn, e);
		}
	}

	public void runFloatc2(float f1, float f2) {
		var fn = (Floatc2) this.fn;
		if (checkFn(fn)) return;
		try {
			fn.get(f1, f2);
		} catch (LinkageError e) {
			onNoSuchMethodError(fn, e);
		}
	}

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

	private void doRemove() {
		if (onRemove != null) {
			try {
				onRemove.run();
			} catch (Throwable t) {
				HotSwapAgent.error("[UpdateRef] onRemove failed: " + t.getMessage(), t);
			}
		} else if (element != null) {
			element.remove();
		}
	}

	/**
	 * NoSuchMethodError 可能是：
	 * 1) 宿主 lambda 被 HotSwap 删除后残留的旧引用；
	 * 2) lambda 内部深处调用到的、与热重载无关的方法。
	 * 两种情况都清掉 fn 并移除元素，但打印日志方便区分。
	 */
	private void onNoSuchMethodError(Object f, LinkageError e) {
		HotSwapAgent.info("[UpdateRef] NoSuchMethodError from " + (f == null ? "?" : f.getClass().getName())
		                  + ": " + e.getMessage());
		clearFn();
		Core.app.post(this::doRemove);
	}


	private boolean checkFn(Object f) {
		if (f == null) {
			// fn 已被清空（HotSwap 删除或 NoSuchMethodError 兜底），移除元素
			// element 可能为 null（wrap 调用方传入 null 的极端情况）
			Core.app.post(this::doRemove);
			return true;
		}
		return false;
	}

}