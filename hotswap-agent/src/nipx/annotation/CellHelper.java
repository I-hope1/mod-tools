package nipx.annotation;

import java.lang.annotation.*;

/**
 * 标记一个方法或类为 Cell 创建/配置辅助方法（Helper）。
 *
 * <p>被标记的方法（或类中的所有方法）在静态分析期会被虚拟内联展开到调用方的链模型中，
 * 在运行时通过 StackWalker 剥除 helper 帧以精确定位调用宿主。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface CellHelper {
}
