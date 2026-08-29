package hope.magic.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在方法上，用于生成目标类私有/受保护字段的快速访问器（Getter 或 Setter）。
 * <p>必须在方法 Javadoc 中使用 {@code @see TargetClass#fieldName} 关联目标字段。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface HField {
	/**
	 * 如果为 {@code true} 代表 Getter，为 {@code false} 代表 Setter。
	 */
	boolean isGetter();
}
