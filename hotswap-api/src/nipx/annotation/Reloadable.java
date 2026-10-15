package nipx.annotation;

import java.lang.annotation.*;

/**
 * 可热重载类型标记注解。
 *
 * <p>用于显式标识目标类支持热重载，便于热更管线、工具链或 UI 配置进行过滤、识别与集中管理。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Reloadable {
}
