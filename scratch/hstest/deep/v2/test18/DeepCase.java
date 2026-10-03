package test18;

/** v2：删掉第一个（doA 那条链整条消失）。 */
public class DeepCase {

	public void run(Runnable r) { }

	public void build() {
		run(() -> Time.run(10, () -> Time.run(5, () -> doB())));
	}

	void doA() { System.out.println("A"); }
	void doB() { System.out.println("B"); }
}
