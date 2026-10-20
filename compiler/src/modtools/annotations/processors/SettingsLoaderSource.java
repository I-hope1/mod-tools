package modtools.annotations.processors;

/**
 * 生成 {@code SettingsLoader.load()} 的方法体文本。
 * <p>
 * {@code SettingsLoader} 由 {@code Filer} 以纯文本写出（不经过 AST），所以这里也只做字符串拼接。
 * 抽出来单独放，是因为这段文本的正确性没法靠 javac 在生成阶段兜住：
 * 引号、嵌套类的 {@code $} 分隔符、缩进、末尾换行，任何一处写错都只会在下游编译时才炸。
 */
final class SettingsLoaderSource {
	private SettingsLoaderSource() {}

	/**
	 * 追加一行「强制加载某个 Settings 类」的语句。
	 * <p>
	 * 用 {@code Class.forName} 而不是直接引用类名，是因为这里只需要触发目标类的静态初始化；
	 * 万一当前 round 拿不到它（增量编译），{@code ClassNotFoundException} 也应当被吞掉。
	 *
	 * @param binaryName JVM binary name：嵌套类用 {@code $} 分隔。
	 *                   {@code ClassSymbol.flatname} 给出的正是这个格式，可直接传入。
	 */
	static void appendLoad(StringBuilder out, CharSequence binaryName) {
		out.append("try { Class.forName(\"").append(binaryName)
		 .append("\"); } catch (ClassNotFoundException ignored) {}\n");
	}

	/**
	 * 把累积的语句缩进成 {@code load()} 的方法体。
	 * <p>
	 * 空内容返回空串，否则会在方法体里留下一个只有空格的空行。
	 */
	static String body(StringBuilder out) {
		String text = out.toString().trim();
		if (text.isEmpty()) return "";
		return "    " + text.replace("\n", "\n    ") + "\n";
	}
}
