package test22;

/** 基线的两条三层链。 */
public class Ablate {

	public void run(Runnable r) { }

	public void build() {
		run(() -> Time5.run(10, () -> Time5.run(5, () -> doA())));
		run(() -> Time5.run(10, () -> Time5.run(5, () -> doB())));
	}

	void doA() { }
	void doB() { }
	void doC() { }
}
