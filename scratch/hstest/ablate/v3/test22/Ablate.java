package test22;

/**
 * 消融用例：B 链叶子被改体（doB -> doB2），且 B 链序号已整体位移（无同名可用）。
 * 若结构证据（calleesPairTo 的"子配给了谁"逐层传递）真的起作用，
 * B 链的外层与中层应保住它们在**上一轮基线**里的名字；否则它们会拿新名字。
 */
public class Ablate {

	public void run(Runnable r) { }

	public void build() {
		run(() -> noop());
		run(() -> Time5.run(10, () -> Time5.run(5, () -> doA())));
		run(() -> Time5.run(10, () -> Time5.run(5, () -> doB2())));
	}

	void noop() { }
	void doA() { }
	void doB() { }
	void doB2() { }
	void doC() { }
}
