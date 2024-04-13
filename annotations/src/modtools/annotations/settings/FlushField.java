package modtools.annotations.settings;


import java.lang.annotation.*;

/**
 * <p>添加到枚举字段上，自动刷新字段</p>
 * <pre>{@code @SettingsInit
 * enum Settings {
 *  	// 自动刷新XXX.xxx
 *  	@FlushCode
 *  	aaa(int.class, XXX.xxx, 0, 10)
 *  	//...
 * }}</pre>
 * @see modtools.annotations.processors.ContentProcessor#dealElement(com.sun.tools.javac.code.Symbol.ClassSymbol) )
 * @see modtools.utils.JSFunc
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.SOURCE)
public @interface FlushField {
}
