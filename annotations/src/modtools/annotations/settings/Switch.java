package modtools.annotations.settings;

import java.lang.annotation.*;

/**
 * <p>添加到枚举字段上，表示为有开关控制</p>
 * {@snippet lang="java" :
 * @SettingsInit
 * enum Settings {
 *   // 自动刷新XXX.xxx
 *   @Switch
 *   aaa(int.class, XXX.xxx, 0, 10)
 *   // 依赖多个开关：全部打开时才生效
 *   @Switch(dependency = {"hot_swap", "profile"})
 *   bbb(int.class, XXX.xxx, 0, 10)
 *   //...
 * }}
 * @see modtools.annotations.processors.ContentProcessor#dealElement(com.sun.tools.javac.code.Symbol.ClassSymbol) )
 * @see modtools.utils.JSFunc
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.SOURCE)
public @interface Switch {
	/**
	 * 依赖的开关键，可以有多个：<b>全部</b>为 true 时这一项才生效。
	 * <p>留空表示用自身的 {@code <name>$enabled}。
	 * <p>{@code dependency = "a"} 与 {@code dependency = {"a"}} 等价。
	 */
	String[] dependency() default {};
}
