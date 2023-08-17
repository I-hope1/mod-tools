package modtools.annotations;

import java.lang.annotation.*;

/**
 * <p>标记在枚举类，自动创建data。</p>
 * <p>{@link modtools.annotations.processors.DataProcessor IconsProcessor}根据assets/icons下的文件
 * 生成对于的字段</p>
 * @see modtools.ui.HopeIconsc
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface DataEnum {
}
