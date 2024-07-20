package modtools.annotations;

import java.lang.annotation.*;

/**
 * <p>用于引导创建{@code Icons}</p>
 * <p>{@link modtools.annotations.processors.IconsProcessor IconsProcessor}根据assets/icons下的文件
 * 生成对于的字段</p>
 * @see modtools.ui.gen.HopeIconsc
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface IconAnn {
	Class<?> mainClass();
	String iconDir() default "sprites/icons";
	String genPackage() default ".";
}
