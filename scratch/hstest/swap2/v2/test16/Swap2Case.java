package test16;

/** V2：删掉第一个（O0 没了），剩下的外层 N0 内含 doB。 */
public class Swap2Case {

	public Runnable r1;
	public Runnable r2;

	public void run(Runnable r) { r.run(); }

	public void build() {
		run(() -> Time.run(10, () -> doB()));
	}

	void doA() { System.out.println("A"); }
	void doB() { System.out.println("B"); }
}
