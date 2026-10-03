package test10;

/** V3：把 b 的 lambda 方法体改成 b2()，位置仍是唯一那个 lambda。 */
public class DelCase {

	public Runnable r1;
	public Runnable r2;

	public void run(Runnable r) { }

	public void build() {
		run(() -> b2());
	}

	void a() { System.out.println("a"); }
	void b2() { System.out.println("b2"); }
}
