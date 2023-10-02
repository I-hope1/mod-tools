package modtools.annotations;

import java.lang.annotation.*;

/** 必须先标记在类上 */
@Target({ElementType.LOCAL_VARIABLE, ElementType.TYPE, ElementType.TYPE_USE})
@Retention(RetentionPolicy.SOURCE)
public @interface OptimizeReflect {
	boolean isSetter() default false;
}
