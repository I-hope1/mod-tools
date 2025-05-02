package modtools.annotations.asm;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface Inline {
	@Target({ElementType.PARAMETER, ElementType.LOCAL_VARIABLE})
	@interface Capture {
	}

	class CaptureVar {
		public static <T> T CAPTURED() {
			return null;
		}
	}
}
