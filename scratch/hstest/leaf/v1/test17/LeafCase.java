package test17;

/** 同一方法里两个同形叶子 lambda，体不同：A -> alpha()，B -> beta()。 */
public class LeafCase {

	public void run(Runnable r) { }

	public void build() {
		run(() -> alpha());
		run(() -> beta());
	}

	void alpha() { }
	void beta() { }
	void gamma() { }
}
