package test15;

/**
 * v2：把两个承载方法对调，**同时**让序号也位移
 * （先插一个同形的新承载方法，使原有两块的相对顺序反转）。
 */
public class CollideCase {

	public Runnable rA;
	public Runnable rB;
	public Runnable rC;

	public void run(Runnable r) { r.run(); }

	public void carrierB() {
		rB = () -> run(() -> b());
	}

	public void carrierC() {
		rC = () -> run(() -> c());
	}

	public void carrierA() {
		rA = () -> run(() -> a());
	}

	void a() { System.out.println("a"); }
	void b() { System.out.println("b"); }
	void c() { System.out.println("c"); }
}
