package test24;

/** 基线（V2）：B 链还在，叶子是 doB；无 noop。 */
public class Compete {

	public void run(Runnable r) { }

	public void build() {
		run(() -> Time7.run(10, () -> Time7.run(5, () -> doB())));
	}

	void doA() { }
	void doB() { }
	void doB2() { }
	void noop() { }
}
