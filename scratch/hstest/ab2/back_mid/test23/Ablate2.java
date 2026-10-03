package test23;

public class Ablate2 {

	public void run(Runnable r) { }

	public void build() {
		run(() -> Time6.run(10, () -> Time6.run(5, () -> doA())));
		run(() -> Time6.run(10, () -> Time6.run(5, () -> doB())));
		run(() -> noop());
	}

	void doX() { }
	void noop() { }
	void doA() { }
	void doB() { }
	void doB2() { }
}
