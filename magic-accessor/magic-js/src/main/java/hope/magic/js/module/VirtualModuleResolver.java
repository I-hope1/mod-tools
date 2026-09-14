package hope.magic.js.module;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存与虚拟模块解析器。
 * 支持通过 Java API 直接向模块系统注册虚拟代码或已初始化的 Java 原生对象。
 */
public class VirtualModuleResolver implements ModuleResolver {
	private final Map<String, ModuleSource> modules = new ConcurrentHashMap<>();

	/**
	 * 带有预置实例对象的虚拟模块源。
	 */
	public static final class InstanceModuleSource extends ModuleSource {
		private final Object instance;

		public InstanceModuleSource(String id, Object instance) {
			super("virtual:" + id, "", "virtual:" + id, "virtual:", false);
			this.instance = instance;
		}

		public Object getInstance() {
			return instance;
		}
	}

	public VirtualModuleResolver() {
	}

	/**
	 * 注册虚拟模块源码。
	 *
	 * @param id   模块名，例如 "my-module" 或 "util/math"
	 * @param code JavaScript 源码
	 */
	public VirtualModuleResolver register(String id, String code) {
		if (id == null || code == null) return this;
		String cleanId = id.trim();
		String virtId = cleanId.startsWith("virtual:") ? cleanId : "virtual:" + cleanId;
		modules.put(cleanId, new ModuleSource(virtId, code, virtId, "virtual:", cleanId.endsWith(".json")));
		return this;
	}

	/**
	 * 直接注册一个 Java 对象实例作为模块的 exports。
	 * 在 JS 中调用 require(id) 时将直接返回该实例，无需经过 JS 编译与执行。
	 *
	 * @param id       模块名
	 * @param instance Java 导出实例对象
	 */
	public VirtualModuleResolver registerInstance(String id, Object instance) {
		if (id == null) return this;
		String cleanId = id.trim();
		modules.put(cleanId, new InstanceModuleSource(cleanId, instance));
		return this;
	}

	public boolean hasModule(String id) {
		if (id == null) return false;
		String cleanId = id.trim();
		if (cleanId.startsWith("virtual:")) cleanId = cleanId.substring("virtual:".length());
		return modules.containsKey(cleanId);
	}

	public void unregister(String id) {
		if (id == null) return;
		String cleanId = id.trim();
		if (cleanId.startsWith("virtual:")) cleanId = cleanId.substring("virtual:".length());
		modules.remove(cleanId);
	}

	@Override
	public ModuleSource resolve(String specifier, JSModule parentModule) {
		if (specifier == null) return null;
		String key = specifier.trim();
		if (key.startsWith("virtual:")) {
			key = key.substring("virtual:".length());
		}

		ModuleSource source = modules.get(key);
		if (source != null) return source;

		// 尝试去除或补充 .js 后缀
		if (key.endsWith(".js")) {
			source = modules.get(key.substring(0, key.length() - 3));
			if (source != null) return source;
		} else {
			source = modules.get(key + ".js");
			if (source != null) return source;
		}

		return null;
	}
}
