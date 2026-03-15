package nipx.profiler;

import nipx.HotSwapAgent;
import mindustry.gen.Building;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;

public class DynamicProfilerAPI {
	/**
	 * @param baseClass  基类，如 Building.class, Unit.class
	 * @param methodName 方法名
	 * @param enable     开启或关闭
	 */
	public static void toggleEntityProbe(Class<?> baseClass, String methodName, boolean enable) {
		String target = "*." + methodName;
		if (enable) { ProfilerData.dynamicTargets.add(target); } else ProfilerData.dynamicTargets.remove(target);

		// 获取所有已加载的子类并重转换
		List<Class<?>> toRetransform = new ArrayList<>();
		Instrumentation inst         = HotSwapAgent.getInst();
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

	/**
	 * 开启/关闭指定方法的统计探针，并自动热重载所有 Building 子类
	 * @param methodName 要统计的方法名，例如 "updateTile", "draw", "acceptItem"
	 * @param enable     true为开启注入，false为卸载注入
	 */
	public static void toggleBuildingProbe(String methodName, boolean enable) {
		String target = "*." + methodName; // 拦截所有类的该方法（由于我们下面只重载 Building 子类，所以范围是精准的）

		if (enable) {
			ProfilerData.dynamicTargets.add(target);
			ProfilerData.clear(); // 重新开始计时
			HotSwapAgent.info("Probe INSTALLED for: " + target);
		} else {
			ProfilerData.dynamicTargets.remove(target);
			HotSwapAgent.info("Probe UNINSTALLED for: " + target);
		}

		// 执行热重载魔法：获取 JVM 中已加载的所有类，筛选出 Building 的子类进行重新转换
		Instrumentation inst          = HotSwapAgent.getInst(); // 必须将 HotSwapAgent.inst 设为 public
		List<Class<?>>  toRetransform = new ArrayList<>();

		for (Class<?> clazz : inst.getAllLoadedClasses()) {
			// 找出所有继承自 Building 的类 (例如 RouterBuild, CoreBuild 等)
			if (Building.class.isAssignableFrom(clazz)) {
				// 确保类是可以被修改的
				if (inst.isModifiableClass(clazz) && !clazz.isArray()) {
					toRetransform.add(clazz);
				}
			}
		}

		try {
			// 触发 retransform。这会让 JVM 重新把字节码喂给 MyClassFileTransformer
			inst.retransformClasses(toRetransform.toArray(new Class[0]));
			HotSwapAgent.info("Successfully retransformed " + toRetransform.size() + " Building classes.");
		} catch (Exception e) {
			HotSwapAgent.error("Failed to retransform Building classes", e);
		}
	}
}