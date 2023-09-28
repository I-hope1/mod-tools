package modtools.annotations.fieldinit;

import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.SOURCE)
public @interface DataBoolFieldInit {
	String data() default "SETTINGS";
}
