package modtools.annotations.builder;

import java.lang.annotation.*;

/** 将方法中的数组转成设置 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface DataBoolSetting {
	/* 用于语言文件 */
	String prefix() default "@settings.";
}
