package test22;

/**
 * 消融用基线：在 B 链**前面**插入一个无关 lambda，使 B 链的 javac 序号整体位移。
 * 这一步不改 B 链的叶子体，B 链应能靠指纹/语义证据保住名字（对照组）。
 */
public class Ablate {

	public void run(Runnable r) { }

	public void build() {
		run(() -> noop());
		run(() -> Time5.run(10, () -> Time5.run(5, () -> doA())));
		run(() -> Time5.run(10, () -> Time5.run(5, () -> doB())));
	}

	void noop() { }
	void doA() { }
	void doB() { }
	void doC() { }
}
