package hope.magic.js.module;

/**
 * 模块解析器接口。
 * 负责根据模块标识符 (specifier) 和当前引用方的父模块解析得到模块源码。
 */
@FunctionalInterface
public interface ModuleResolver {
	/**
	 * 解析模块源。
	 *
	 * @param specifier    模块标识，例如 "./math", "../utils.js", "config.json", "classpath:res/tool.js" 等
	 * @param parentModule 当前发起加载的父模块（若为全局/顶层调用则为 null）
	 * @return 成功解析则返回 ModuleSource，若该解析器无法解析则返回 null
	 */
	ModuleSource resolve(String specifier, JSModule parentModule);
}
