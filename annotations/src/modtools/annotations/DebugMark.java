package modtools.annotations;

import java.lang.annotation.*;


@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface DebugMark {
	/**
	 * <p>{@code %}：表达式
	 * <p>{@code @}：值
	 */
	String fmt() default "% = @";
}
