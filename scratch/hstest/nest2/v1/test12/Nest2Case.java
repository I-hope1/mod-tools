package test12;

/**
 * 两个承载方法各挂一个"同形"lambda，lambda 体内各自调用一个 handler。
 * 两个 handler 体内再调不同的叶子方法。
 *
 * 关键：外层 lambda 的体是 `handler()`，而 handler 是**本类合成方法**，
 * 其名字会被 #SYNTHETIC_METHOD# 屏蔽 —— 于是两个外层 lambda 的指纹应当相同。
 */
public class Nest2Case {

	public Runnable r1;
	public Runnable r2;

	public void buildA() {
		r1 = () -> handleA();
	}

	public void buildB() {
		r2 = () -> handleB();
	}

	void handleA() { leafX(); }
	void handleB() { leafY(); }

	void leafX() { System.out.println("X"); }
	void leafY() { System.out.println("Y"); }
}
