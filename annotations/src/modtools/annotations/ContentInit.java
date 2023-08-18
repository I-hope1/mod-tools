package modtools.annotations;


import java.lang.annotation.*;

/**
 * <p>用于初始化对于content对象</p>
 * <pre>{@code @ContentInit
 * class Contents {
 *   // fields
 *   public static void load() {}
 * }}</pre>
 * <p>这会生成</p>
 * <pre>{@code @ContentInit
 * class Contents {
 *   // fields
 *   public static void load() {
 *     $field1$ = new $Field1$()
 *     // ....
 *   }
 * }}</pre>
 * @see modtools.annotations.processors.ContentProcessor#dealElement
 * @see modtools.ui.Contents
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface ContentInit {
}
