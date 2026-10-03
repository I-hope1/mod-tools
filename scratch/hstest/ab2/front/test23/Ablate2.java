package test23;

/**
 * 变形一（前插，与现有夹具同构）：无关叶子在 B 链之前。
 * 期望：位置证据反着指 —— noop 编号更小、先被处理，若只看位置它会抢走旧叶子名。
 */
public class Ablate2 {

	public void run(Runnable r) { }

	public void build() {
		run(() -> noop());
		run(() -> Time6.run(10, () -> Time6.run(5, () -> doA())));
		run(() -> Time6.run(10, () -> Time6.run(5, () -> doB2())));
	}

	void noop() { }
	void doA() { }
	void doB() { }
	void doB2() { }
}
