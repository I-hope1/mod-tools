package nipx.profiler;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

import static nipx.HotSwapAgent.info;

public class ProfilerData {
	// 记录方法名 -> 总耗时 (纳秒)
	public static final Map<String, LongAdder> totalTime      = new ConcurrentHashMap<>();
	// 记录方法名 -> 调用次数
	public static final Map<String, LongAdder> callCount      = new ConcurrentHashMap<>();
	public static final Set<String>            dynamicTargets = new CopyOnWriteArraySet<>();

	/**
	 * 判断某个类的方法是否需要被注入探针
	 * @param slashClassName 例如 "mindustry/gen/Building"
	 * @param methodName     例如 "updateTile"
	 */
	public static boolean isTargeted(String slashClassName, String methodName) {
		// 规则1：精确匹配 (例如: "mindustry/world/blocks/production/GenericCrafter$GenericCrafterBuild.updateTile")
		if (dynamicTargets.contains(slashClassName + "." + methodName)) return true;
		// 规则2：匹配类下所有方法 (例如: "mindustry/gen/Building.*")
		if (dynamicTargets.contains(slashClassName + ".*")) return true;
		// 规则3：匹配所有类的指定方法 (例如: "*.updateTile")
		if (dynamicTargets.contains("*." + methodName)) return true;

		return false;
	}

	public static void clear() {
		info("Clearing profiler data...");
		totalTime.clear();
		callCount.clear();
	}

	// 打印报告
	public static void printReport() {
		info("=== Profiler Report ===");
		totalTime.forEach((method, nanosAdder) -> {
			long   nanos = nanosAdder.sum();
			long   count = callCount.computeIfAbsent(method, k -> new LongAdder()).sum();
			double avgMs = (nanos / 1_000_000.0) / count;
			info(String.format("[%s] Total: %d ms, Calls: %d, Avg: %.4f ms%n",
			 method, nanos / 1_000_000, count, avgMs));
		});
	}

	// ASM 注入的代码会调用这个
	public static void record(String method, long nanos) {
		totalTime.computeIfAbsent(method, k -> new LongAdder()).add(nanos);
		callCount.computeIfAbsent(method, k -> new LongAdder()).increment();
	}

	public static void recordBuilding(Object obj, long nanos) {
		String label = obj.getClass().getSimpleName();
		record(label, nanos);
	}
}
