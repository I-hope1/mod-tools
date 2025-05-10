package modtools.annotations.asm;

import java.lang.annotation.*;

/**
 * @see modtools.annotations.asm.ASMProccessor */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.SOURCE)
public @interface CopyConstValue {
}
