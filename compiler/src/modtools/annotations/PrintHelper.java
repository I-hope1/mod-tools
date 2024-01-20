package modtools.annotations;

import java.io.*;

public interface PrintHelper {
	default void print(Object... objects) {
		for (Object object : objects) {
			System.out.println(object);
		}
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
	default void print(String str, Object... objects) {
		System.out.println(format(str, objects));
	}
	default void err(Throwable th) {
		StringWriter sw = new StringWriter();
		PrintWriter  pw = new PrintWriter(sw);
		th.printStackTrace(pw);
		err(sw.toString());
	}
	static void errs(Object... objects) {
		for (Object object : objects) {
			System.err.println(object);
		}
	}
	default void err(Object... objects) {
		errs(objects);
	}
}
