package modtools.annotations.settings;

import java.lang.annotation.*;

/** 内部api，将字段读写改为对settings的key的读写 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS) // .class 文件里保留
public @interface RefOf {
	Class<?> value();
}