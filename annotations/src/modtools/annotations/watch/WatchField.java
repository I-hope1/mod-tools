package modtools.annotations.watch;

import java.lang.annotation.*;

/**
 * 给字段添加监视
 *
 * @see test0.Private
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.SOURCE)
public @interface WatchField {
	float interval() default 1;
	String group() default "";
	WatchUpdater updater() default WatchUpdater.def;
	// Class<?> caller() default Object.class;
}
