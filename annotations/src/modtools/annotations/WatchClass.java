package modtools.annotations;

import java.lang.annotation.*;

/**
 * <p>用于引导创建{@code WatchWindow}</p>
 * <pre>
 * <code>{@link WatchClass @WatchClass}
 * <code>class XXX {
 *  {@link WatchField @WatchField}
 *  {@code public static Object obj;}
 * }</code></pre>
 * @see test0.Private
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface WatchClass {
	String[] groups() default {};
}
