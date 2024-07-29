package modtools.annotations;

import java.lang.annotation.*;


@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface CopyMethodFrom {
	/** 完全限定类名#方法名({@link java.lang.invoke.MethodType})  */
	String method();
	String insertBefore() default "";
}
