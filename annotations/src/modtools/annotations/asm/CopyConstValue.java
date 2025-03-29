package modtools.annotations.asm;

import java.lang.annotation.*;

/**
 * 暂时不能是static final字段
 * @see modtools.annotations.asm.ASMProccessor */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.SOURCE)
@Deprecated
public @interface CopyConstValue {
}
