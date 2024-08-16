package modtools.unsupported;

import java.lang.StringTemplate.Processor;

@SuppressWarnings("StringTemplateMigration")
public class HopeProcessor {
	/* 注意.interpolate()都会被替换成字符串拼接的形式 */
	public static final Processor<String, RuntimeException> NPX = string -> modtools.IntVars.modName + "-" + string.interpolate();

	public static void main() {
		System.out.println(NPX."aa{}");
	}
}
