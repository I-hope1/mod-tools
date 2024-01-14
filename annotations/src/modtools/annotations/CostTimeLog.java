package modtools.annotations;

import java.lang.annotation.*;

/**
 * <p>用于打印耗时</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface CostTimeLog {
	String info() default "Costs in @ms";
}
