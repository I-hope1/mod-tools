package modtools.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** 测试夹具：带默认值的注解，用于验证 {@code memberDefaults()} 不被写脏 */
@Retention(RetentionPolicy.RUNTIME)
public @interface FixtureAnno {
	int value() default 7;
}
