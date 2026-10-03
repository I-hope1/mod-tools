package test16;

/** v3：在 build() 开头**插入**一个同形外层（内含 doC），原有两个一字未改。 */
public class Swap2Case {

	public Runnable r1;
	public Runnable r2;

	public void run(Runnable r) { r.run(); }

	public void build() {
		run(() -> Time.run(10, () -> doC()));
		run(() -> Time.run(10, () -> doA()));
		run(() -> Time.run(10, () -> doB()));
	}

	void doA() { System.out.println("A"); }
	void doB() { System.out.println("B"); }
	void doC() { System.out.println("C"); }
}
