package nipx.ref;

import arc.func.*;
import arc.scene.Element;
import arc.scene.ui.TextField.TextFieldValidator;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class UpdateRef {
	public static final List<WeakReference<UpdateRef>> ALL =
	 new CopyOnWriteArrayList<>();


	public static List<WeakReference<UpdateRef>> getAll() {
		return ALL;
	}
	public static void clearLambda() {
		ALL.removeIf(x -> x.get() == null);
	}

	private volatile Object fn;

	/** 弱引用持有元素，避免阻止 GC */
	private final WeakReference<?> elementRef;
	/** 如何"删除"这个元素，由注册方传入 */
	private final Runnable         removeAction;

	private UpdateRef(Object fn, Object element, Runnable removeAction) {
		this.fn = fn;
		this.elementRef = new WeakReference<>(element);
		this.removeAction = removeAction;
		ALL.add(new WeakReference<>(this));
	}

	/**
	 * ASM 注入点：替换 Element.update(Runnable) 的参数。
	 * 返回的 Runnable 是稳定的方法引用，不会被 HotSwap 删除。
	 */
	public static Runnable wrap(Element element, Runnable original) {
		if (returnOriginal(original)) return original;
		UpdateRef ref = new UpdateRef(original, element, element::remove);
		return ref::run;  // ref::run 对应 UpdateRef.run()，永远存在
	}
	public static Prov<?> wrap(Element element, Prov<?> original) {
		if (returnOriginal(original)) return original;
		UpdateRef ref = new UpdateRef(original, element, element::remove);
		return ref::runProv;
	}
	public static Boolp wrap(Element element, Boolp original) {
		if (returnOriginal(original)) return original;
		UpdateRef ref = new UpdateRef(original, element, element::remove);
		return ref::runBoolp;
	}
	public static Cons<?> wrap(Element element, Cons<?> original) {
		if (returnOriginal(original)) return original;
		UpdateRef ref = new UpdateRef(original, element, element::remove);
		return ref::runCons;
	}
	public static Boolf<?> wrap(Element element, Boolf<?> original) {
		if (returnOriginal(original)) return original;
		UpdateRef ref = new UpdateRef(original, element, element::remove);
		return ref::runBoolf;
	}
	public static TextFieldValidator wrap(Element element, TextFieldValidator original) {
		if (returnOriginal(original)) return original;
		UpdateRef ref = new UpdateRef(original, element, element::remove);
		return ref::runValidator;
	}

	private static boolean returnOriginal(Object original) {
		if (original == null) return true;
		if (original.getClass().getName().startsWith(UpdateRef.class.getName())) return true;  // 已被包装
		return false;
	}

	/**
	 * 注册到 element 的稳定引用。
	 * fn == null (被 HotSwap 清除) → 自动删除元素。
	 */
	public void run() {
		var f = (Runnable) this.fn;
		if (checkFn(f)) return;
		try {
			f.run();
		} catch (NoSuchMethodError e) {
			// 极端情况兜底
			clearFn();
		}
	}
	private void clearFn() {
		this.fn = null;
	}

	public <T> T runProv() {
		var f = (Prov<T>) this.fn;
		if (checkFn(f)) return null;
		try {
			return f.get();
		} catch (NoSuchMethodError e) {
			// 极端情况兜底
			clearFn();
			return null;
		}
	}

	public boolean runBoolp() {
		var f = (Boolp) this.fn;
		if (checkFn(f)) return false;
		try {
			return f.get();
		} catch (NoSuchMethodError e) {
			// 极端情况兜底
			clearFn();
			return false;
		}
	}
	@SuppressWarnings("unchecked")
	public <T> void runCons(T t) {
		var f = (Cons<T>) this.fn;
		if (checkFn(f)) return;
		try {
			f.get(t);
		} catch (NoSuchMethodError e) {
			clearFn();
		}
	}
	public <T> boolean runBoolf(T t) {
		var f = (Boolf<T>) this.fn;
		if (checkFn(f)) return false;
		try {
			return f.get(t);
		} catch (NoSuchMethodError e) {
			clearFn();
			return false;
		}
	}
	public boolean runValidator(String t) {
		var f = (TextFieldValidator) this.fn;
		if (checkFn(f)) return false;
		try {
			return f.valid(t);
		} catch (NoSuchMethodError e) {
			clearFn();
			return false;
		}
	}

	private boolean checkFn(Object f) {
		if (f == null) {
			// lambda 已被 HotSwap 删除，移除元素
			if (elementRef.get() != null && removeAction != null) {
				removeAction.run();
			}
			return true;
		}
		return false;
	}


	public boolean clearIfFromClass(String dotClassName) {
		var f = this.fn;
		if (f != null && f.getClass().getName().startsWith(dotClassName + "$$Lambda")) {
			clearFn();
			return true;
		}
		return false;
	}
}