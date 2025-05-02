package modtools.annotations;

import com.sun.tools.javac.comp.CompileStates.CompileState;
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
		static void err(Throwable th) {
			StringWriter sw = new StringWriter();
			PrintWriter  pw = new PrintWriter(sw);
			th.printStackTrace(pw);
			String s = sw.toString();
			errs("[MISC]:" + s);

			Replace.log.error(SPrinter.err(s));
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
	}
}
