package hope.magic.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在方法上，用于生成目标类私有/受保护方法的调用器。
 * <p>必须在方法 Javadoc 中使用 {@code @see TargetClass#methodName} 关联目标方法。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface HMethod {
	/**
	 * 当为 {@code true} 时：
	 * <ul>
	 *     <li>常规方法：生成 invokespecial 指令（绕过虚方法派发）</li>
	 *     <li>构造器：调用 {@code <init>} 方法</li>
	 * </ul>
	 */
	boolean isSpecial() default false;
}
