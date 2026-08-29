package hope.magic.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在访问器类上，指定使用的底层生成方案。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface HMarkMagic {
	/**
	 * 实现方案模式：
	 * <ul>
	 *     <li>{@link AccessMode#AUTO}（默认）</li>
	 *     <li>{@link AccessMode#UNSAFE_AND_LINKTO}（Unsafe 字段访问 + HotSpot linkToXX 方法直调）</li>
	 *     <li>{@link AccessMode#UNSAFE_AND_METHODHANDLE}（Unsafe 字段访问 + Android / 通用 MethodHandle 直调）</li>
	 *     <li>{@link AccessMode#MAGIC_ACCESSOR}（传统 MagicAccessorImpl 字节码特权方案，适用于 JDK &le; 21）</li>
	 * </ul>
	 */
	AccessMode mode() default AccessMode.AUTO;

	/**
	 * 仅在 {@link AccessMode#MAGIC_ACCESSOR} 模式下生效，指定生成的辅助类继承的基类。
	 */
	Class<?> magicClass() default hope.magic.runtime.MAGICIMPL.class;
}
