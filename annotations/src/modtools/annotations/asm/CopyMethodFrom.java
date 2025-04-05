package modtools.annotations.asm;

import java.lang.annotation.*;


/** 依赖@see来获取引用  */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface CopyMethodFrom {
	String insertBefore() default "";
}
