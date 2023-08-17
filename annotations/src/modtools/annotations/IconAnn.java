package modtools.annotations;

import java.lang.annotation.*;

/**
 * <p>用于引导创建{@code Icons}</p>
 * <p>{@link modtools.annotations.processors.IconsProcessor IconsProcessor}根据assets/icons下的文件
 * 生成对于的字段</p>
 * @see modtools.ui.HopeIconsc
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface IconAnn {
	Class<?> mainClass();
	String iconDir() default "icons";
}
