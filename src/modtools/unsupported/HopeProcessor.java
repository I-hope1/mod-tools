package modtools.unsupported;

import arc.func.Cons;
import arc.util.Log;
import modtools.annotations.DebugMark;

import java.lang.StringTemplate.Processor;

@SuppressWarnings("StringTemplateMigration")
@DebugMark
public class HopeProcessor {
	/** 注意：
	 * <p>.interpolate()都会被替换成字符串拼接的形式
	 * <p>.values().get(i)都会被替换 为 对应表达式
	 * <p>.fragments().get(i)都会被替换 为 字符串片段
	 **/
	public static final Processor<String, RuntimeException> NPX = string -> modtools.IntVars.modName + "-" + string.interpolate();

	public static void main() {
		String aa = "10203";
		int  a   = 1, b = 2;
		Cons<String> run = d -> {
			System.out.println(NPX."aa\{aa}");
			Log.info(a + b + d);
		};
		run.get(aa);
	}
}
