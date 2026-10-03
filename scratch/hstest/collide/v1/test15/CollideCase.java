package test15;

/**
 * 指纹碰撞形态：两个外层 lambda 的体都只是"求值一个内层 lambda"，
 * 内层 lambda 名被 #SYNTHETIC_METHOD# 屏蔽 -> 两个外层 hash 相同。
 * 两个内层分别调 a() / b()。承载方法各挂一个。
 */
public class CollideCase {

	public Runnable rA;
	public Runnable rB;

	public void run(Runnable r) { r.run(); }

	public void carrierA() {
		rA = () -> run(() -> a());
	}

	public void carrierB() {
		rB = () -> run(() -> b());
	}

	void a() { System.out.println("a"); }
	void b() { System.out.println("b"); }
}
