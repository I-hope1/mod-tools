package modtools.annotations;

import com.sun.tools.javac.comp.CompileStates.CompileState;
import com.sun.tools.javac.util.JCDiagnostic.*;
import com.sun.tools.javac.util.JCDiagnostic.Error;
import modtools.annotations.unsafe.Replace;

import java.io.*;

public interface PrintHelper {
	default void println(Object... objects) {
		SPrinter.println(objects);
	}
	static String format(String text, Object... args) {
		if (args.length > 0) {
			StringBuilder out  = new StringBuilder(text.length() + args.length * 2);
			int           argi = 0;
			for (int i = 0; i < text.length(); i++) {
				char c = text.charAt(i);
				if (c == '@' && argi < args.length) {
					out.append(args[argi++]);
				} else {
					out.append(c);
				}
			}

			return out.toString();
		}

		return text;
	}
	default void println(String str, Object... objects) {
		SPrinter.println(str, objects);
	}
	default void err(Throwable th) {
		SPrinter.err(th);
	}
	static void errs(Object... objects) {
		for (Object object : objects) {
			System.err.println(object);
		}
	}
	default void err(Object... objects) {
		errs(objects);
	}

	interface SPrinter {
		static Error err(String err) {
			return new Error("any", "1", err);
		}
		/* static Error errToLog(Throwable th) {
			StringWriter sw = new StringWriter();
			PrintWriter  pw = new PrintWriter(sw);
			th.printStackTrace(pw);
			String s = sw.toString();
			return new Error("any", "1", s);
		} */
		/**
		 * 上报处理器内部错误：打印堆栈，并<b>中止本轮编译</b>。
		 * <p>
		 * 正常诊断请用 {@code log.error(SPrinter.err(msg))}，不要走这里——这里的语义是“处理器自身出问题了”。
		 */
		static void err(Throwable th) {
			StringWriter sw = new StringWriter();
			PrintWriter  pw = new PrintWriter(sw);
			th.printStackTrace(pw);
			String s = sw.toString();
			errs("[MISC]:" + s);

			if (Replace.log == null) {
				throw new RuntimeException(th);
			}
			abortCompilation(s);
		}

		/**
		 * 中止编译的唯一出口：记一条 error，并让 javac 停在 {@link CompileState#INIT}。
		 * 用于处理器无法安全继续的场景（半成品树继续走下去只会产出更难查的错误）。
		 */
		static void abortCompilation(String message) {
			Replace.log.error(SPrinter.err(message));
			Replace.compiler.shouldStopPolicyIfError = CompileState.INIT;
		}
		static void println(Object... objects) {
			for (Object object : objects) {
				System.out.println(object);
			}
		}

		static void println(String str, Object... objects) {
			System.out.println(format(str, objects));
		}
		static void println(String str) {
			System.out.println(str);
		}
		static Warning warn(String s) {
			return new Warning("any", "1", s);
		}
		static Note note(String s) {
			return new Note("any", "1", s);
		}
	}
}
