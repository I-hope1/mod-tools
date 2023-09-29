package modtools.annotations.builder;

import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.SOURCE)
public @interface DataBoolFieldInit {
	/* 为""时为this.data() */
	String data() default "SETTINGS";
}
