package modtools.annotations;


import java.lang.annotation.*;

/** 移动包 到对应 包（targetPackage）  */
@Target(ElementType.PACKAGE)
@Retention(RetentionPolicy.SOURCE)
public @interface MoveToPackage {
	String targetPackage();
}
