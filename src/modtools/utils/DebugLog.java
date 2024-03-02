package modtools.utils;

import arc.util.Log;

/** 调用{@link arc.util.Log}<br>
 * TODO: 打印信息包含变量名 */
public class DebugLog {
	public static void info(Object... args) {
		Log.info("@, @, @, @", args);
	}
}
