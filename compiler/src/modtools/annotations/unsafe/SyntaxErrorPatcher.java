package modtools.annotations.unsafe;

import com.sun.tools.javac.main.Option;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Options;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * javac 语法错误的“自愈”：把源码里少写的一个字符补上（或多写的一个 {@code )} 删掉），
 * 让这一轮编译还能继续。
 * <p>
 * 这是唯一会**直接改写用户源文件**的地方，所以约束很硬：
 * <ul>
 *   <li>位置越界、非 file scheme、读写失败 —— 一律返回 {@code false}，调用方按“未修正”处理；</li>
 *   <li>写回必须用与 javac 解码时相同的字符集，否则非 ASCII 源码会被写坏；</li>
 *   <li>任何异常都不允许升级为编译错误。</li>
 * </ul>
 * 诊断位置始终基于**原始**文件，因此内部维护一个累计位移 {@link #offset()}。
 */
public final class SyntaxErrorPatcher {
	private final Charset charset;
	private       int     offset;

	public SyntaxErrorPatcher(Charset charset) {
		this.charset = charset;
	}

	/** 与 javac 解码源码时使用的字符集保持一致（{@code -encoding} 优先，否则平台默认） */
	public static SyntaxErrorPatcher forContext(Context context) {
		return new SyntaxErrorPatcher(charsetOf(context));
	}

	public static Charset charsetOf(Context context) {
		try {
			String encoding = Options.instance(context).get(Option.ENCODING);
			if (encoding != null && !encoding.isEmpty()) return Charset.forName(encoding);
		} catch (Throwable ignored) { }
		return Charset.defaultCharset();
	}

	/** 已应用编辑造成的累计位移 */
	public int offset() {
		return offset;
	}

	// region 纯函数：便于单测，不碰 I/O
	/** 在 {@code index} 处插入一个字符；越界返回 {@code null} */
	public static String insert(String source, int index, char ch) {
		if (source == null || index < 0 || index > source.length()) return null;
		return new StringBuilder(source).insert(index, ch).toString();
	}

	/** 删除 {@code index} 处的字符；越界返回 {@code null} */
	public static String delete(String source, int index) {
		if (source == null || index < 0 || index >= source.length()) return null;
		return new StringBuilder(source).deleteCharAt(index).toString();
	}
	// endregion

	/**
	 * 在诊断位置补一个字符；该位置本来就是 {@code ')'} 时改为删除它。
	 *
	 * @return {@code true} 表示文件已被改写
	 */
	public boolean fix(JavaFileObject file, int diagnosticPosition, char inserted) throws IOException {
		String source = read(file);
		if (source == null) return false;
		int index = diagnosticPosition + offset;
		// 与诊断语义一致：只能落在文件内部，不能追加到末尾
		if (index < 0 || index >= source.length()) return false;

		String patched = source.charAt(index) == ')' ? delete(source, index) : insert(source, index, inserted);
		if (patched == null || !write(file, patched)) return false;
		offset++;
		return true;
	}

	/**
	 * 删除诊断位置上的多余字符。
	 *
	 * @return {@code true} 表示文件已被改写
	 */
	public boolean drop(JavaFileObject file, int diagnosticPosition) throws IOException {
		String source = read(file);
		if (source == null) return false;
		String patched = delete(source, diagnosticPosition + offset);
		if (patched == null || !write(file, patched)) return false;
		offset--;
		return true;
	}

	private String read(JavaFileObject file) throws IOException {
		CharSequence content = file.getCharContent(true);
		return content == null ? null : content.toString();
	}

	private boolean write(JavaFileObject file, String content) throws IOException {
		URI uri = file.toUri();
		if (!"file".equals(uri.getScheme())) return false;
		Files.writeString(Paths.get(uri), content, charset);
		return true;
	}
}
