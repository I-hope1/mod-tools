package modtools.annotations.asm;

import java.lang.annotation.*;


/** 用于生成pool的supplier  */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface GenPool {
}
