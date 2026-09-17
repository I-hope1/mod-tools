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
	 * Unsafe (字段) + linkTo* (方法) 方案：
	 * <ul>
	 *     <li><b>编译期/生成期：</b>自动生成专属 {@code java.lang.invoke.MagicBridge} 字节码。</li>
	 *     <li><b>运行期：</b>将桥接类直接注入到 Bootstrap ClassLoader 的 {@code java.lang.invoke} 包下，直接发射 JVM 虚拟机底层原语指令：
	 *         {@code linkToSpecial}、{@code linkToStatic}、{@code linkToVirtual}、{@code linkToInterface}。</li>
	 *     <li><b>字段访问：</b>通过 {@code Unsafe} 内存偏移量直接读写，零反射开销。</li>
	 * </ul>
	 *
	 * <h4>相比传统 MethodHandle / invokeExact 的核心架构优势：</h4>
	 * <ol>
	 *     <li><b>极低 C2 JIT 编译器内联预算消耗 (Inlining Budget)：</b><br>
	 *         标准 {@code MethodHandle} 调用链极长，由 {@code invokeExact} 触发后须历经 {@code LambdaForm$MH/...}、
	 *         {@code speciesData}、{@code guardWithTest} 及多层 adapter。HotSpot C2 编译器的内联树深度受限（默认 {@code MaxInlineLevel=9}，
	 *         {@code MaxInlineSize=35}），MH 的多层胶水代码往往迅速耗尽 C2 的内联预算，导致目标业务方法被放弃内联（Inlining Bailout）。
	 *         而 linkTo 方案由 Invoker 直接通过 {@code invokestatic} 呼叫桥接方法，桥接方法内部直连 JVM 原语并尾随 {@code MemberName}，
	 *         调用图极度扁平（仅 1~2 层），几乎不消耗 C2 预算，将宝贵的内联额度完全留给核心业务逻辑。</li>
	 *     <li><b>彻底规避同构签名引发的递归与深度检测内联截断 (Avoid Recursive/Depth Inline Cutoff)：</b><br>
	 *         HotSpot C2 对签名相同的方法调用链（例如通用泛型签名 {@code (Object, Object[])Object} 或多层通用的 LambdaForm 模板）
	 *         设有严格的递归调用检测与内联深度上限（{@code MaxRecursiveInlineLevel} 默认仅为 1）。当多个动态调用链嵌套或在循环中频繁触发
	 *         同构签名的 MethodHandle 适配器时，C2 会判定为潜在递归进而提前熔断内联。
	 *         linkTo 方案结合针对参数数量特化的具体方法（如 {@code invoke0~3}、{@code newInstance0~3}），彻底摆脱了泛型同构包装，
	 *         从根本上杜绝了 C2 的同构方法内联熔断。</li>
	 *     <li><b>零 MethodHandle 对象头与 LambdaForm 元空间膨胀 (Metaspace &amp; GC Friendly)：</b><br>
	 *         复合型 MethodHandle 组合（如 {@code filterArguments}、{@code dropArguments} 等）会在 Java 堆上派生大量包装对象，
	 *         并在元空间产生诸多匿名 {@code LambdaForm} 类。linkTo 桥接类结构极度紧凑纯粹（单静态方法 + 静态 {@code @Stable MemberName}），
	 *         无任何多余运行时对象，堆与元空间占用极低。</li>
	 *     <li><b>杜绝运行期动态类型校验与类型污染 (Polymorphic Type-Pollution Free)：</b><br>
	 *         {@code MethodHandle.invokeExact} 在未完全内联或多态场景下需动态核对 {@code MethodType} 签名，具有运行时性能惩罚和去优化风险；
	 *         linkTo 桥接调用为确定的静态方法调用，类型安全已在字节码验证期完成，执行期零动态类型校验开销。</li>
	 *     <li><b>无视 Java 语言级访问权限限制 (Native Privilege Bypass)：</b><br>
	 *         linkTo 原语属于 JVM 特权内部指令，直接以底层的 {@code MemberName}（vtable/itable 偏移或直接方法指针）进行调度，
	 *         天然穿透 {@code private}、{@code package-private} 及跨模块访问壁垒，执行效率等同于普通原生字节码指令。</li>
	 *     <li><b>零数组分配与零参数装箱 (Zero-Allocation)：</b><br>
	 *         特化生成的调用方法直接在虚拟机栈上传递固定参数槽，彻底杜绝反射中常见的 {@code new Object[]{...}} 参数数组分配与堆逃逸。</li>
	 * </ol>
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
	MAGIC_ACCESSOR,

	/**
	 * Nestmate 隐藏类方案（Plan C）：
	 * 利用 JEP 181 (Nest-Based Access Control) 与 JEP 371 (Hidden Classes)，
	 * 在目标类的巢元作用域中动态定义同巢隐藏类，直接通过原生字节码指令
	 * (invokevirtual / invokespecial / invokestatic) 直调私有成员，达成极致单态内联与 100% 类加载器安全卸载。
	 * <p><b>适用平台：</b>HotSpot JVM（JDK 15+ 首选；JDK 8~14 降级为 VM 匿名类）。</p>
	 */
	NESTMATE
}
