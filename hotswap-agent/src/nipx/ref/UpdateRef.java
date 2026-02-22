package nipx.ref;

import arc.scene.Element;
import nipx.annotation.Tracker;

import java.lang.ref.WeakReference;

@Tracker
public class UpdateRef {

	private volatile Runnable fn;

	/** 弱引用持有元素，避免阻止 GC */
	private final WeakReference<?> elementRef;
	/** 如何"删除"这个元素，由注册方传入 */
	private final Runnable         removeAction;

	public UpdateRef(Runnable fn, Object element, Runnable removeAction) {
		this.fn = fn;
		this.elementRef = new WeakReference<>(element);
		this.removeAction = removeAction;
	}

	/**
	 * ASM 注入点：替换 Element.update(Runnable) 的参数。
	 * 返回的 Runnable 是稳定的方法引用，不会被 HotSwap 删除。
	 */
	public static Runnable wrap(Element element, Runnable original) {
		UpdateRef ref = new UpdateRef(original, element, element::remove);
		return ref::run;  // ref::run 对应 UpdateRef.run()，永远存在
	}

	/**
	 * 注册到 element 的稳定引用。
	 * fn == null (被 HotSwap 清除) → 自动删除元素。
	 */
	public void run() {
		var f = this.fn;
		if (f == null) {
			// lambda 已被 HotSwap 删除，移除元素
			if (elementRef.get() != null && removeAction != null) {
				removeAction.run();
			}
			return;
		}
		try {
			f.run();
		} catch (NoSuchMethodError e) {
			// 极端情况兜底
			this.fn = null;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public boolean clearIfFromClass(String dotClassName) {
		Runnable f = this.fn;
		if (f != null && f.getClass().getName().startsWith(dotClassName + "$$Lambda")) {
			this.fn = null;
			return true;
		}
		return false;
	}
}