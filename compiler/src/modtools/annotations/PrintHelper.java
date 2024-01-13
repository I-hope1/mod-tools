package modtools.annotations;

import java.io.*;

public interface PrintHelper {
	default void print(Object... objects) {
		for (Object object : objects) {
			System.out.println(object);
		}
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
