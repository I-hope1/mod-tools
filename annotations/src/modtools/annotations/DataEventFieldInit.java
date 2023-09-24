package modtools.annotations;

import java.lang.annotation.*;

/**
 * <p>用于初始化字段的值，并注册事件</p>
 * 需要方法{@code public void dataInit(){}}
 * 只需调用这个方法
 * <hr>
 * 示例：{@code @DataFieldInit boolean xxx;}
 * <p>这会生成：</p>
 * <pre>{@code
 * MyEvents $event$ = new MyEvents();
 * public void dataInit() {
 *   $event$.onIns(xxx, t -> xxx = t.enabled())
 * }
 * }</pre>
 *
 * @see modtools.annotations.processors.DataProcessor#process
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.SOURCE)
public @interface DataEventFieldInit {
}
