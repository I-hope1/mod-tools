package test10;

/** V1：build() 挂两个 lambda，$0 -> a()，$1 -> b()。 */
public class DelCase {

	public Runnable r1;
	public Runnable r2;

	public void run(Runnable r) { }

	public void build() {
		run(() -> a());
		run(() -> b());
	}

	void a() { System.out.println("a"); }
	void b() { System.out.println("b"); }
}
