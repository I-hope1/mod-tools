package modtools.annotations.settings;

import java.lang.annotation.*;

/**
 * <p>添加到枚举字段上，表示为有开关控制</p>
 * {@snippet lang="java" :
 * @SettingsInit
 * enum Settings {
 *  	// 自动刷新XXX.xxx
 *  	@Switch
 *  	aaa(int.class, XXX.xxx, 0, 10)
 *  	//...
 * }}
 * @see modtools.annotations.processors.ContentProcessor#dealElement(com.sun.tools.javac.code.Symbol.ClassSymbol) )
 * @see modtools.utils.JSFunc
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.SOURCE)
public @interface Switch {
	String dependency() default "";
}
