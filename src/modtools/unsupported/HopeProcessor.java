package modtools.unsupported;

import arc.util.Log;
import modtools.annotations.DebugMark;

import java.lang.StringTemplate.Processor;

@SuppressWarnings("StringTemplateMigration")
@DebugMark
public class HopeProcessor {
	/* 注意.interpolate()都会被替换成字符串拼接的形式 */
	public static final Processor<String, RuntimeException> NPX = string -> modtools.IntVars.modName + "-" + string.interpolate();

	public static void main() {
		System.out.println(NPX."aa{}");
		int a = 1, b = 2;
		Log.info(a + b);
	}
}
