package modtools.annotations.asm;

import java.lang.annotation.*;

public class HAccessor {
	@Target(ElementType.TYPE)
	@Retention(RetentionPolicy.SOURCE)
	@Repeatable(value = HFields.class)
	public @interface HField {
		Class<?> base();
		String name();
	}
	@Target(ElementType.TYPE)
	@Retention(RetentionPolicy.SOURCE)
	@Repeatable(value = HMethods.class)
	public @interface HMethod {
		Class<?> base();
		/** {@code <init> for constructor}  */
		String name();
		Class<?>[] params();
	}

	@Target(ElementType.TYPE)
	@Retention(RetentionPolicy.SOURCE)
	public @interface HFields {
		HField[] value();
	}
	@Target(ElementType.TYPE)
	@Retention(RetentionPolicy.SOURCE)
	public @interface HMethods {
		HMethod[] value();
	}
}
