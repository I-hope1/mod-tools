package modtools.annotations.asm;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface Sample {
	// boolean internal() default false;
	/**
	 * <p>是否开放包私有的方法
	 * <p>如果继承的类中有java内部包名(java.lang, jdk.internal, ...)，这个是必须启用的
	 */
	boolean openPackagePrivate() default false;

	/** 当visit的对象为Object.class时，更改的类 */
	String defaultClass() default "";

	@interface SampleForMethod {
		Class<?>[] upperBoundClasses() default {};
	}
	@interface SampleForInitializer {
		Class<?>[] upperBoundClasses() default {};
	}

	class AConstants {
		public static final  String INTERFACE_SUFFIX      = "Interface";
		public static final  String GEN_CLASS_NAME_SUFFIX = "h$C";
		public static final  String SUPER_METHOD_PREFIX   = "super$$";
		private static final String GEN_CLASS_NAME        = "modtools.gen.GenX";

		public static String legalName(String name) {
			return name.replace("java", "lava").replace("jdk", "ldk");
		}
		public static String nextGenClassName() {
			return GEN_CLASS_NAME + AConstants.nextGenID();
		}
		private static int lastID = 0;
		private static int nextGenID() {
			return ++lastID;
		}
	}

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
