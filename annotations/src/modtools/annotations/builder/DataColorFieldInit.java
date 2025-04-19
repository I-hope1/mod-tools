package modtools.annotations.builder;

import java.lang.annotation.*;

/** {@snippet lang="java": SettingsUI.colorBlock(t, "pad", data(), "padColor", padColor, c -> padColor = c.rgba());} */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.SOURCE)
public @interface DataColorFieldInit {
	/** 在settingColor中添加 */
	boolean needSetting() default false;
	/** 如果为空，在content中查找  */
	String data() default "SETTINGS";
	/** 删除字段的前缀 */
	String fieldPrefix() default "";
}
