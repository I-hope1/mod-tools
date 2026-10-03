package test16;

/**
 * V1：build() 里两个外层 lambda，体完全相同，唯一差别是内层 lambda 指向 doA / doB。
 *   O0 -> Time.run(10, () -> doA())
 *   O1 -> Time.run(10, () -> doB())
 */
public class Swap2Case {

	public Runnable r1;
	public Runnable r2;

	public void run(Runnable r) { r.run(); }

	public void build() {
		run(() -> Time.run(10, () -> doA()));
		run(() -> Time.run(10, () -> doB()));
	}

	void doA() { System.out.println("A"); }
	void doB() { System.out.println("B"); }
}
