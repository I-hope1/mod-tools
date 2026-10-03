package test21;

/** V1：两条三层链。 */
public class Save3 {

	public void run(Runnable r) { }

	public void build() {
		run(() -> Time4.run(10, () -> Time4.run(5, () -> doA())));
		run(() -> Time4.run(10, () -> Time4.run(5, () -> doB())));
	}

	void doA() { }
	void doB() { }
}
