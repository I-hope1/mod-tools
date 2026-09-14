package hope.magic.js.module;

import hope.magic.js.compiler.JSCompiler;
import hope.magic.js.runtime.JSContext;
import hope.magic.js.runtime.JSFunction;
import hope.magic.js.runtime.JSObject;
import hope.magic.js.runtime.JSOps;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CommonJS 模块管理器。
 * 负责模块解析、生命周期调度、缓存管理与循环依赖防御。
 */
public class JSModuleManager {
	private static final ThreadLocal<JSModule> CURRENT_MODULE = new ThreadLocal<>();

	public static JSModule getCurrentModule() {
		return CURRENT_MODULE.get();
	}

	private final JSContext              cx;
	private       ModuleResolver         resolver;
	private final Map<String, JSModule>  cache           = new ConcurrentHashMap<>();
	private final JSObject               cacheJsObject   = new JSObject();
	private       JSModule               mainModule;
	private       RequireFunction        globalRequire;

	public JSModuleManager(JSContext cx) {
		this(cx, new CompositeModuleResolver());
	}

	public JSModuleManager(JSContext cx, ModuleResolver resolver) {
		this.cx = Objects.requireNonNull(cx, "JSContext cannot be null");
		this.resolver = resolver != null ? resolver : new CompositeModuleResolver();
		this.globalRequire = new RequireFunction(this, null);
	}

	public JSContext getContext() {
		return cx;
	}

	public ModuleResolver getResolver() {
		return resolver;
	}

	public void setResolver(ModuleResolver resolver) {
		this.resolver = Objects.requireNonNull(resolver, "ModuleResolver cannot be null");
	}

	public Map<String, JSModule> getCache() {
		return cache;
	}

	public JSObject getCacheObject() {
		return cacheJsObject;
	}

	public JSModule getMainModule() {
		return mainModule;
	}

	public void setMainModule(JSModule mainModule) {
		this.mainModule = mainModule;
	}

	public RequireFunction getRequireFunction() {
		return globalRequire;
	}

	public RequireFunction createRequireFunction(JSModule currentModule) {
		return new RequireFunction(this, currentModule);
	}

	/**
	 * 同步加载并执行指定 specifier 的模块，返回其 exports。
	 *
	 * @param specifier    模块标识（路径、名称等）
	 * @param parentModule 发起调用的父模块（顶层可为 null）
	 * @return 模块导出的对象 (module.exports)
	 */
	public Object require(String specifier, JSModule parentModule) {
		JSModule module = load(specifier, parentModule);
		return module.getExports();
	}

	/**
	 * 加载指定 specifier 的模块并返回完整的 JSModule 对象。
	 *
	 * @param specifier    模块标识
	 * @param parentModule 父模块
	 * @return 已加载或正在加载中的 JSModule
	 */
	public JSModule load(String specifier, JSModule parentModule) {
		ModuleSource source = resolver.resolve(specifier, parentModule);
		if (source == null) {
			throw JSContext.makeError("Cannot find module '" + specifier + "'");
		}

		String id = source.getId();
		JSModule cached = cache.get(id);
		if (cached != null) {
			return cached;
		}

		JSModule module = new JSModule(id, source.getFilename(), source.getDirname(), parentModule);
		if (mainModule == null) {
			mainModule = module;
			globalRequire.put("main", mainModule);
		}

		// 核心关键：在执行模块代码前先行入缓存，防御循环依赖死循环
		cache.put(id, module);
		cacheJsObject.put(id, module);

		JSModule prevModule = CURRENT_MODULE.get();
		CURRENT_MODULE.set(module);
		try {
			if (source instanceof VirtualModuleResolver.InstanceModuleSource ims) {
				// 宿主直接注入的 Java 对象实例
				module.setExports(ims.getInstance());
			} else if (source.isJson()) {
				// JSON 格式模块自动解析
				Object jsonObj = cx.eval("(" + source.getCode() + ")");
				module.setExports(jsonObj);
			} else {
				// 普通 JavaScript 模块，编译为模块函数包装器执行
				JSFunction moduleFunc = JSCompiler.compileModule(source.getCode(), source.getFilename());
				RequireFunction localRequire = createRequireFunction(module);
				Object[] args = new Object[]{
						module.getExports(),
						localRequire,
						module,
						source.getFilename(),
						source.getDirname()
				};
				moduleFunc.call(cx, module.getExports(), args);
			}
			module.setLoaded(true);
		} catch (Throwable t) {
			// 若执行失败，从缓存中移除避免污染
			cache.remove(id);
			cacheJsObject.delete(id);
			if (t instanceof RuntimeException re) throw re;
			throw new RuntimeException("Error loading module '" + specifier + "': " + t.getMessage(), t);
		} finally {
			if (prevModule != null) CURRENT_MODULE.set(prevModule);
			else CURRENT_MODULE.remove();
		}

		return module;
	}

	/**
	 * 异步加载模块并返回 ES 模块命名空间对象的 Promise (支持 dynamic import())。
	 */
	public hope.magic.js.runtime.JSPromise importDynamic(String specifier, JSModule parentModule) {
		hope.magic.js.runtime.JSPromise promise = new hope.magic.js.runtime.JSPromise(cx);
		hope.magic.js.runtime.JSPromise.enqueueMicrotask(cx, () -> {
			try {
				JSModule loaded = load(specifier, parentModule);
				Object exports = loaded.getExports();
				JSObject ns = new JSObject();
				ns.put(hope.magic.js.runtime.JSSymbol.TO_STRING_TAG, "Module");
				if (exports instanceof JSObject expObj) {
					for (String key : expObj.keys()) {
						if (!hope.magic.js.runtime.JSSymbol.isSymbolKey(key)) {
							ns.put(key, expObj.get(key));
						}
					}
					if (!ns.has("default")) {
						ns.put("default", expObj);
					}
				} else {
					ns.put("default", exports);
				}
				promise.fulfill(ns);
			} catch (Throwable t) {
				promise.reject(t);
			}
		});
		return promise;
	}

	/**
	 * 符合 CommonJS / Node.js 规范的 require 函数对象。
	 */
	public static class RequireFunction extends JSObject implements JSFunction {
		private final JSModuleManager manager;
		private final JSModule        currentModule;

		public RequireFunction(JSModuleManager manager, JSModule currentModule) {
			super(JSContext.LazyFunction.FUNCTION_PROTOTYPE);
			this.manager = manager;
			this.currentModule = currentModule;

			put("name", "require");
			put("length", 1);
			put("cache", manager.getCacheObject());
			if (manager.getMainModule() != null) {
				put("main", manager.getMainModule());
			}
			put("resolve", JSContext.makeMethod("resolve", 1, (cx, thisObj, args) -> {
				if (args == null || args.length == 0 || args[0] == null) {
					throw JSContext.makeTypeError("The \"id\" argument must be of type string");
				}
				String spec = JSOps.toStr(args[0]);
				ModuleSource src = manager.getResolver().resolve(spec, currentModule);
				if (src == null) {
					throw JSContext.makeError("Cannot find module '" + spec + "'");
				}
				return src.getId();
			}));
		}

		@Override
		public Object call(JSContext cx, Object thisObj, Object[] args) throws Throwable {
			if (args == null || args.length == 0 || args[0] == null) {
				throw JSContext.makeTypeError("The \"id\" argument must be of type string");
			}
			String specifier = JSOps.toStr(args[0]);
			return manager.require(specifier, currentModule);
		}

		@Override
		public String toString() {
			return "function require() { [native code] }";
		}
	}
}
