package nipx.uihook;

import nipx.HotSwapAgent;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;

public class UIHookRegistry {
	public record UILocation(String className, String methodName, int lineNumber, int index) { }

	// 使用弱引用，当 UI 界面销毁时，垃圾回收器会自动清理，不留任何隐患
	public static final Map<UILocation, List<WeakReference<Object>>> registry = new ConcurrentHashMap<>();

	/**
	 * 插桩注入的目标方法，用于运行时收集实例
	 */
	public static void register(Object obj, String className, String methodName, int lineNumber, int index) {
		if (obj == null) return;
		Object element = obj;
		try {
			// 如果返回的是 Cell，提取其内部真实的 Element (Label/Button 等)
			if (obj.getClass().getName().equals("arc.scene.ui.layout.Cell")) {
				java.lang.reflect.Field field = obj.getClass().getField("element");
				element = field.get(obj);
			}
		} catch (Exception ignored) { }

		if (element == null) return;

		UILocation loc = new UILocation(className, methodName, lineNumber, index);
		registry.computeIfAbsent(loc, k -> new CopyOnWriteArrayList<>())
		 .add(new WeakReference<>(element));
	}

	/**
	 * 精准定向更新文本
	 */
	public static void updateText(String className, String methodName, int lineNumber, int index, String newText) {
		UILocation                  loc  = new UILocation(className, methodName, lineNumber, index);
		List<WeakReference<Object>> refs = registry.get(loc);
		if (refs == null) return;

		for (WeakReference<Object> ref : refs) {
			Object elem = ref.get();
			if (elem == null) continue;

			try {
				Class<?>                 clazz         = elem.getClass();
				java.lang.reflect.Method setTextMethod = null;

				// 1. 尝试直接反射修改 setText
				try {
					setTextMethod = clazz.getMethod("setText", CharSequence.class);
				} catch (NoSuchMethodException e) {
					try {
						setTextMethod = clazz.getMethod("setText", String.class);
					} catch (NoSuchMethodException ex) {
						// 2. 如果是 TextButton，尝试获取其内部 Label 并修改
						try {
							java.lang.reflect.Method getLabel = clazz.getMethod("getLabel");
							Object                   label    = getLabel.invoke(elem);
							if (label != null) {
								java.lang.reflect.Method setLabelText = label.getClass().getMethod("setText", CharSequence.class);
								setLabelText.invoke(label, newText);
							}
						} catch (Exception ignored) { }
					}
				}

				if (setTextMethod != null) {
					setTextMethod.invoke(elem, newText);
				}

				// 3. 触发 UI 重新布局计算，保证尺寸正确自适应
				try {
					java.lang.reflect.Method invalidateHierarchy = clazz.getMethod("invalidateHierarchy");
					invalidateHierarchy.invoke(elem);
				} catch (Exception e) {
					try {
						java.lang.reflect.Method getParent = clazz.getMethod("getParent");
						Object                   parent    = getParent.invoke(elem);
						if (parent != null) {
							java.lang.reflect.Method invalidate = parent.getClass().getMethod("invalidateHierarchy");
							invalidate.invoke(parent);
						}
					} catch (Exception ignored) { }
				}
			} catch (Exception e) {
				HotSwapAgent.error("Failed to dynamically update UI element: " + elem, e);
			}
		}
	}
}