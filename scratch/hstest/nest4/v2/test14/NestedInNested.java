package test14;

/** v2：只把两个承载方法的声明顺序对调。 */
public class NestedInNested {

	public Runnable r1;
	public Runnable r2;

	public void run(Runnable r) { r.run(); }

	public void buildB() {
		r2 = () -> run(() -> b());
	}

	public void buildA() {
		r1 = () -> run(() -> a());
	}

	void a() { System.out.println("a"); }
	void b() { System.out.println("b"); }
}
