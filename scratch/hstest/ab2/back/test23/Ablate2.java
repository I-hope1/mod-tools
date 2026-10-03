package test23;

/**
 * 变形二（后插）：无关叶子在 B 链**之后**。
 * 期望：位置证据站在对的一边（doB2 先被处理），这个变形用于对照。
 */
public class Ablate2 {

	public void run(Runnable r) { }

	public void build() {
		run(() -> Time6.run(10, () -> Time6.run(5, () -> doA())));
		run(() -> Time6.run(10, () -> Time6.run(5, () -> doB2())));
		run(() -> noop());
	}

	void noop() { }
	void doA() { }
	void doB() { }
	void doB2() { }
}
