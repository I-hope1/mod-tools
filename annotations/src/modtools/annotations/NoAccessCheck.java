package modtools.annotations;


import java.lang.annotation.*;

/** 对类禁用访问检查  */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface NoAccessCheck {}