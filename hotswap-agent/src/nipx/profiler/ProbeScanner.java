package nipx.profiler;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

public class ProbeScanner {
	// 预设的常用性能监控建议
	private static final Set<String> RECOMMENDED_METHODS = Set.of(
	 "update", "updateTile", "draw", "handleItem", "acceptItem",
	 "move", "getPowerProduction", "onRead", "onWrite", "remove"
	);

	public static List<String> findCandidateMethods(Class<?> clazz) {
		return Arrays.stream(clazz.getMethods())
		 .filter(m -> m.getDeclaringClass() != Object.class)
		 .map(Method::getName)
		 .filter(name -> !name.startsWith("get") && !name.startsWith("set") && !name.contains("$"))
		 .distinct()
		 .sorted((a, b) -> {
			 // 推荐的方法排在前面
			 boolean ra = RECOMMENDED_METHODS.contains(a);
			 boolean rb = RECOMMENDED_METHODS.contains(b);
			 if (ra && !rb) return -1;
			 if (!ra && rb) return 1;
			 return a.compareTo(b);
		 })
		 .collect(Collectors.toList());
	}
}