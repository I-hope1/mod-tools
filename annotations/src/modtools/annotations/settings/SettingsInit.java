package modtools.annotations.settings;


import java.lang.annotation.*;

/**
 * <p>用于初始化对于Settings对象</p>
 * <pre>{@code @SettingsInit
 * enum Settings {
 * }}</pre>
 * @see modtools.annotations.processors.ContentProcessor#dealElement(com.sun.tools.javac.code.Symbol.ClassSymbol) )
 * @see modtools.utils.JSFunc
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface SettingsInit {
	String value() default ".";
	String parent() default "";
	/** 如果data不为null，则到  */
	String data() default "";
	/** 是否会触发事件，MyEvents.fire(this)  */
	boolean fireEvent() default false;
}
