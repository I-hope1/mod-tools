package modtools.annotations;

import java.lang.annotation.*;


/** 默认将Log.info(xxx) -> Log.info("xxx = " + xxx) */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.SOURCE)
public @interface DebugMark {
	/**
	 * <p>{@code %}：表达式
	 * <p>{@code @}：值
	 */
	String fmt() default "% = @";
}
