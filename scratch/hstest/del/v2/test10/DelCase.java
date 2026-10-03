package test10;

/** V2：删掉第一个 lambda，b 的 lambda 从 $1 变成 $0。方法体一字未改。 */
public class DelCase {

	public Runnable r1;
	public Runnable r2;

	public void run(Runnable r) { }

	public void build() {
		run(() -> b());
	}

	void a() { System.out.println("a"); }
	void b() { System.out.println("b"); }
}
