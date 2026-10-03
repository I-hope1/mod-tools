package test18;

/** 三层嵌套：外层 -> 中层 -> 叶子，每层都是 lambda。 */
public class DeepCase {

	public void run(Runnable r) { }

	public void build() {
		run(() -> Time.run(10, () -> Time.run(5, () -> doA())));
		run(() -> Time.run(10, () -> Time.run(5, () -> doB())));
	}

	void doA() { System.out.println("A"); }
	void doB() { System.out.println("B"); }
}
