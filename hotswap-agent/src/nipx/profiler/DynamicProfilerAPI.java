package nipx.profiler;

import arc.func.Cons;
import mindustry.entities.Effect;
import mindustry.entities.Effect.EffectContainer;
import mindustry.gen.Building;
import nipx.HotSwapAgent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

	/**
	 * Effect.draw 是 DrawEffect 函数式字段，无法 retransform。
	 * 直接用反射把字段替换成计时包装器，toggle 时还原原始值。
	 */
	private static final Map<Effect, Cons<EffectContainer>> originalEffectDraws = new ConcurrentHashMap<>();
	private static       Field                              drawField;

	private static Field getDrawField() throws ReflectiveOperationException {
		if (drawField == null) {
			drawField = Effect.class.getDeclaredField("draw");
			drawField.setAccessible(true);
		}
		return drawField;
	}

	/**
	 * @param effect     要探针的 Effect 实例（如 Fx.explosion）
	 * @param recordName 记录到 ProfilerData 的 key（建议用字段名，如 "Fx.explosion"）
	 * @param enable     开启或关闭
	 */
	public static void toggleEffectProbe(Effect effect, String recordName, boolean enable) {
		try {
			Field field = getDrawField();
			if (enable) {
				if (originalEffectDraws.containsKey(effect)) return; // 已经包装过，跳过
				var original = (Cons<EffectContainer>) field.get(effect);
				originalEffectDraws.put(effect, original);
				field.set(effect, (Cons<EffectContainer>) e -> {
					long start = System.nanoTime();
					original.get(e);
					ProfilerData.record(recordName, System.nanoTime() - start);
				});
				HotSwapAgent.info("Effect probe INSTALLED: " + recordName);
			} else {
				var original = originalEffectDraws.remove(effect);
				if (original != null) {
					field.set(effect, original);
					HotSwapAgent.info("Effect probe UNINSTALLED: " + recordName);
				}
			}
		} catch (ReflectiveOperationException e) {
			HotSwapAgent.error("Failed to toggle effect probe: " + recordName, e);
		}
	}

	/** 关闭所有 Effect 探针并还原原始 draw */
	public static void clearAllEffectProbes() {
		originalEffectDraws.forEach((effect, original) -> {
			try {
				getDrawField().set(effect, original);
			} catch (ReflectiveOperationException e) {
				HotSwapAgent.error("Failed to restore effect draw", e);
			}
		});
		originalEffectDraws.clear();
	}
}