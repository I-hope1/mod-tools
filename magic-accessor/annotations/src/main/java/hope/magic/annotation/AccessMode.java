package hope.magic.annotation;

/**
 * 访问器底层实现模式。
 */
public enum AccessMode {
	/**
	 * 自动选择：
	 * 默认采用全版本通用的 Unsafe 字段访问 + invokedynamic (indy) 动态调用点方案。
	 */
	AUTO,

	/**
	 * Unsafe (字段) + invokedynamic (方法) 方案：
	 * <ul>
	 *     <li><b>字段访问：</b>通过 {@code Unsafe} 内存偏移量直接读写，零反射开销。</li>
	 *     <li><b>方法调用：</b>通过 JVM 原生 {@code invokedynamic} (indy) 指令与 {@code ConstantCallSite} 绑定，首次调用由 BSM 解析，后续执行由 JIT 深度内联为机器码，零额外开销。</li>
	 * </ul>
	 * <b>适用平台：</b>所有支持 invokedynamic 的 JVM（JDK 8 ~ 25+，包括 Android 8.0+）。
	 */
	UNSAFE_AND_INDY,

	/**
	 * @deprecated 请使用 {@link #UNSAFE_AND_INDY}
	 */
	@Deprecated
	UNSAFE_AND_LINKTO,

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
