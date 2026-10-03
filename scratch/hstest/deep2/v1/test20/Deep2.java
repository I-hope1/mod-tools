package test20;

/** V1：两条三层链，doA 链与 doB 链。 */
public class Deep2 {

	public void run(Runnable r) { }

	public void build() {
		run(() -> Time3.run(10, () -> Time3.run(5, () -> doA())));
		run(() -> Time3.run(10, () -> Time3.run(5, () -> doB())));
	}

	void doA() { }
	void doB() { }
	void doC() { }
}
