package hope.magic.annotation;

/**
 * 访问器底层实现模式。
 */
public enum AccessMode {
	/**
	 * 自动选择：
	 * 默认采用编译期生成专属 MagicBridge + 运行期注入 java.lang.invoke 的 linkToXX 直调方案。
	 */
	AUTO,

	/**
	 * Unsafe (字段) + linkToXX (方法) 方案：
	 * <ul>
	 *     <li><b>编译期：</b>收集项目所需方法签名，自动生成专属 {@code java.lang.invoke.MagicBridge} 字节码并以 Base64 存储在 {@code MagicBridgeData}。</li>
	 *     <li><b>运行期：</b>在 {@code Magic.install()} 时将桥接类直接注入到 Bootstrap ClassLoader 的 {@code java.lang.invoke} 包下，直接执行 JVM 原生 {@code linkToSpecial / linkToStatic / linkToVirtual} 指令。</li>
	 *     <li><b>字段访问：</b>通过 {@code Unsafe} 内存偏移量直接读写，零反射开销。</li>
	 * </ul>
	 * <b>适用平台：</b>HotSpot JVM（全 JDK 8 ~ 25+）。
	 */
	UNSAFE_AND_LINKTO,

	/**
	 * Unsafe (字段) + invokedynamic (方法) 方案：
	 * <ul>
	 *     <li><b>字段访问：</b>通过 {@code Unsafe} 内存偏移量直接读写。</li>
	 *     <li><b>方法调用：</b>通过 JVM 原生 {@code invokedynamic} (indy) 指令与 {@code ConstantCallSite} 绑定，由 JIT 深度内联为机器码。</li>
	 * </ul>
	 * <b>适用平台：</b>所有支持 invokedynamic 的 JVM（JDK 8 ~ 25+，包括 Android 8.0+）。
	 */
	UNSAFE_AND_INDY,

	/**
	 * Unsafe (字段) + MethodHandle.invokeExact (方法) 方案：
	 * <ul>
	 *     <li><b>字段访问：</b>通过 {@code Unsafe} 内存偏移量直接读写。</li>
	 *     <li><b>方法调用：</b>通过标准 {@code MethodHandle.invokeExact} 进行调用。</li>
	 * </ul>
	 * <b>适用平台：</b>Android (ART VM) 及跨 VM 平台。
	 */
	UNSAFE_AND_METHODHANDLE,

	/**
	 * 传统 MagicAccessorImpl 方案：
	 * 利用 ASM 生成继承自 {@code MagicAccessorImpl} 的辅助类直接执行私有字节码指令。
	 * <p><b>适用平台：</b>JDK &le; 21（在 JDK 22+ 已移除）。</p>
	 */
	MAGIC_ACCESSOR
}
