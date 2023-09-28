package modtools.annotations;

import java.lang.annotation.Repeatable;

public class SettingsBuilder {
	@Repeatable(Bools.class)
	public @interface Bool {
		boolean value();
		/** 设置的前缀 */
		String prefix() default "settings";
		String data() default "SETTINGS";
	}
	@interface Bools {
		Bool[] value();
	}
}
