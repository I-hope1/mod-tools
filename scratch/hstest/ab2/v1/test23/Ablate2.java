package test23;

/** 基线：两条三层链，无插入。 */
public class Ablate2 {

	public void run(Runnable r) { }

	public void build() {
		run(() -> Time6.run(10, () -> Time6.run(5, () -> doA())));
		run(() -> Time6.run(10, () -> Time6.run(5, () -> doB())));
	}

	void doX() { }
	void noop() { }
	void doA() { }
	void doB() { }
	void doB2() { }
}
