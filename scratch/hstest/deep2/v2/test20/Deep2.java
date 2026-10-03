package test20;

/**
 * V2 变体甲：只改 doB 链的叶子方法体（doB -> doB2），其余不动。
 * 序号不变、两条链都在。预期：靠 Step 2 同名兜底，整条链名字都应该保住。
 */
public class Deep2 {

	public void run(Runnable r) { }

	public void build() {
		run(() -> Time3.run(10, () -> Time3.run(5, () -> doA())));
		run(() -> Time3.run(10, () -> Time3.run(5, () -> doB2())));
	}

	void doA() { }
	void doB() { }
	void doB2() { }
}
