package modtools.annotations;


import java.lang.annotation.*;

/**
 * <p>用于初始化对于content对象</p>
 * {@snippet lang="java" :
 * @ContentInit
 * class Contents {
 *   // fields
 *   public static void load() {}
 * }}
 * <p>这会生成</p>
 * {@snippet lang="java" :
 * @ContentInit
 * class Contents {
 *   // fields
 *   public static void load() {
 *     $field1$ = new $Field1$()
 *     // ....
 *   }
 * }}
 * @see modtools.annotations.processors.ContentProcessor#dealElement
 * @see modtools.ui.Contents
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface ContentInit {
}
