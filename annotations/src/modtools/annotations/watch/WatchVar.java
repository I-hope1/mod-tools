package modtools.annotations.watch;

import java.lang.annotation.*;

/**
 * 给局部变量添加监视
 * @see test0.Private */
@Target(ElementType.LOCAL_VARIABLE)
@Retention(RetentionPolicy.SOURCE)
public @interface WatchVar {
	float interval() default 1;
	String group() default "";
	WatchUpdater updater() default WatchUpdater.def;

	// Class<?>[] classes() default {};
}
