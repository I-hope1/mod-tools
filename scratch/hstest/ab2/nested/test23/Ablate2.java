package test23;

/**
 * 变形三（带子的插入，最有区分力）：插入的是"自己有一个子"的两层 lambda。
 * 其外层形状 (()) 与 B 链中层相同、其后代叶子形状 () 与 B 链叶子相同，
 * 因此同时受检 sameNestingLevel 的拦截与 calleesPairTo 的传递。
 */
public class Ablate2 {

	public void run(Runnable r) { }

	public void build() {
		run(() -> Time6.run(10, () -> doX()));            // 新插入：两层
		run(() -> Time6.run(10, () -> Time6.run(5, () -> doA())));
		run(() -> Time6.run(10, () -> Time6.run(5, () -> doB2())));
	}

	void doX() { }
	void doA() { }
	void doB() { }
	void doB2() { }
}
