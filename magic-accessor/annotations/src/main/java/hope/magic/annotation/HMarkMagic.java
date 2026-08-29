package hope.magic.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在包含 {@link HField} 或 {@link HMethod} 的类上。
 * 编译期处理器会为该类生成继承自 {@link #magicClass()} 的辅助字节码类，
 * 利用 JVM 的 MagicAccessorImpl 特权绕过可见性检查。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface HMarkMagic {
	/**
	 * 生成的辅助类继承的基类，默认为 {@link hope.magic.runtime.MAGICIMPL}。
	 */
	Class<?> magicClass() default hope.magic.runtime.MAGICIMPL.class;
}
