package modtools.annotations;

import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.SOURCE)
public @interface DataColorFieldInit {
	boolean needSetting() default false;
}
