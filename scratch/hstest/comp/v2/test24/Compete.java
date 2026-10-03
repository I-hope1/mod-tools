package test24;

/**
 * 同一次保存里同时做两件事：
 *   • 插入一个全新的叶子 lambda（noop）—— 没有指纹证据；
 *   • 把 B 链叶子改体（doB -> doB2）—— 同样没有指纹证据。
 * 旧侧只剩叶子名 $5 可被认领，两个新叶子争一个名字。
 * 这才是"叶子归属是否由组内顺序决定"的真正实验。
 */
public class Compete {

	public void run(Runnable r) { }

	public void build() {
		run(() -> noop());
		run(() -> Time7.run(10, () -> Time7.run(5, () -> doB2())));
	}

	void doA() { }
	void doB() { }
	void doB2() { }
	void noop() { }
}
