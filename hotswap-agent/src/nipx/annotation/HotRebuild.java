package nipx.annotation;

import java.lang.annotation.*;

/**
 * 标记一个在热重载时可被幂等调用的 UI 重建方法（Fallback Rebuild）。
 *
 * <p>当增量属性修补（CellPropertyRef）无法识别动态或复杂循环站点时，
 * 框架会通过反射调用带有此注解的无参方法（或按 argFields 字段映射参数的方法），
 * 对容器进行整树幂等重建。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface HotRebuild {
	/**
	 * 可选：按位置映射到宿主实例字段名的参数来源列表。
	 * 若为空，表示方法必须为无参方法。
	 */
	String[] argFields() default {};
}
