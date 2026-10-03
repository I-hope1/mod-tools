package test14;

/**
 * 真正符合"占位符吃掉内层身份"的形态：外层 lambda 体内**再定义一个 lambda**，
 * 内层 lambda 是 synthetic 方法（名字会被 #SYNTHETIC_METHOD# 屏蔽）。
 *
 *   buildA: r1 = () -> run(() -> a());
 *   buildB: r2 = () -> run(() -> b());
 */
public class NestedInNested {

	public Runnable r1;
	public Runnable r2;

	public void run(Runnable r) { r.run(); }

	public void buildA() {
		r1 = () -> run(() -> a());
	}

	public void buildB() {
		r2 = () -> run(() -> b());
	}

	void a() { System.out.println("a"); }
	void b() { System.out.println("b"); }
}
