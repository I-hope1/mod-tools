package nipx.ref;

import arc.Core;
import arc.func.*;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.event.EventListener;
import arc.scene.ui.TextField.TextFieldValidator;
import nipx.HotSwapAgent;

import java.util.*;

/** @see nipx.LambdaRef */
public class UpdateRef {

	private volatile Object  fn;
	/** 直接强引用即可：element -> listener -> UpdateRef -> element 只是孤立的环，不影响 GC */
	private final    Element element;

	private UpdateRef(Object fn, Element element) {
		this.fn = fn;
		this.element = element;
	}

	public static Runnable wrap(Element element, Runnable original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element)::run;
	}

	public static Prov<?> wrap(Element element, Prov<?> original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element)::runProv;
	}

	public static Boolp wrap(Element element, Boolp original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element)::runBoolp;
	}

	public static Cons<?> wrap(Element element, Cons<?> original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element)::runCons;
	}

	public static Boolf<?> wrap(Element element, Boolf<?> original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element)::runBoolf;
	}

	public static TextFieldValidator wrap(Element element, TextFieldValidator original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element)::runValidator;
	}

	public static Floatc wrap(Element element, Floatc original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element)::runFloatc;
	}

	public static Floatc2 wrap(Element element, Floatc2 original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element)::runFloatc2;
	}

	public static EventListener wrap(Element element, EventListener original) {
		if (returnOriginal(original)) return original;
		return new UpdateRef(original, element)::runEventListener;
	}

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
		if (element != null) {
			Core.app.post(element::remove);
		}
	}


	private boolean checkFn(Object f) {
		if (f == null) {
			// fn 已被清空（HotSwap 删除或 NoSuchMethodError 兜底），移除元素
			// element 可能为 null（wrap 调用方传入 null 的极端情况）
			if (element != null) {
				Core.app.post(element::remove);
			}
			return true;
		}
		return false;
	}

}