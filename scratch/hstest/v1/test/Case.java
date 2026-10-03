package test;

import java.util.function.Consumer;

public class Case {
	public String field = "x";

	public void run() {
		int loc = 7;
		Consumer<String> c = s -> System.out.println(field + s + loc);
		c.accept("a");
	}

	public void run2() {
		Consumer<String> c = s -> System.out.println(s);
		c.accept("b");
	}

	// 非捕获 this 的 lambda
	public Runnable r1() {
		return () -> System.out.println("hello");
	}
}
