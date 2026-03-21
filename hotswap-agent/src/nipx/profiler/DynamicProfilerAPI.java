package nipx.profiler;

import nipx.HotSwapAgent;

import java.lang.instrument.Instrumentation;
import java.util.*;

public class DynamicProfilerAPI {
	public static final ProfilerTransformer transformer = new ProfilerTransformer();

	public static void init() {
		Instrumentation inst = HotSwapAgent.getInst();
		inst.addTransformer(transformer, true);
	}
	/**
	 * @param baseClass  基类，如 Building.class, Unit.class
	 * @param methodName 方法名
	 * @param enable     开启或关闭
	 */
	public static void toggleEntityProbe(Class<?> baseClass, String methodName, boolean enable) {
		if (enable) {
			ProfilerTransformer.addTargetMethod(baseClass, methodName);
		} else {
			ProfilerTransformer.removeTargetMethod(baseClass, methodName);
		}
		// 获取所有已加载的子类并重转换
		List<Class<?>>  toRetransform = new ArrayList<>();
		Instrumentation inst          = HotSwapAgent.getInst();
		for (Class<?> clazz : inst.getAllLoadedClasses()) {
			if (baseClass.isAssignableFrom(clazz) && !clazz.isInterface() && inst.isModifiableClass(clazz)) {
				toRetransform.add(clazz);
			}
		}

		try {
			inst.retransformClasses(toRetransform.toArray(new Class[0]));
			HotSwapAgent.info("Retransformed " + toRetransform.size() + " classes for " + methodName);
		} catch (Exception e) {
			HotSwapAgent.error("Retransform failed", e);
		}
	}

}