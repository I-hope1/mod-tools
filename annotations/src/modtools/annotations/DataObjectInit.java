package modtools.annotations;

import java.lang.annotation.*;

/**
 * <p>用于初始化对于data对象</p>
 * <pre>{@code @DataFieldInit
 * class E_XXX {
 *   // other_code...
 *   public static Data data;
 * }}</pre>
 * <p>这会创建</p>
 * <pre>{@code
 * class E_XXX {
 *   // other_code...
 *   public static Data data = MySettings.SETTINGS.child("%className%");
 * }
 * }</pre>
 * @see modtools.annotations.processors.DataProcessor#process */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface DataObjectInit {
}
