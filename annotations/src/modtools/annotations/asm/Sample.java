package modtools.annotations.asm;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface Sample {
	// boolean internal() default false;
	/** <p>是否开放包私有的方法
	 * <p>如果继承的类中有内部包名，这个是必须启用的  */
	boolean openPackagePrivate() default false;

	/** 当visit的对象为Object.class时，更改的类 */
	String defaultClass() default "";

	@interface SampleForMethod {
		Class<?>[] targets() default {};
	}

	String INTERFACE_SUFFIX      = "Interface";
	String GEN_CLASS_NAME_SUFFIX = "h$C";
	String SUPER_METHOD_PREFIX   = "super$";

	class SampleTemp {
		/** 会被自动替换为对应的接口调用 */
		public static <T> T _super(T t) {
			return t;
		}
		public static <T> T fieldAccess(Object base, String name) {
			return null;
		}
	}
}
