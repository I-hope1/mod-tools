package test17;

/**
 * v3：两个叶子的方法体都改了 —— alpha->alpha2、beta->beta2。
 * 期望：不得互相抢名字，也不得把旧名字配给语义不同的方法（宁可各自变新名字）。
 */
public class LeafCase {

	public void run(Runnable r) { }

	public void build() {
		run(() -> alpha2());
		run(() -> beta2());
	}

	void alpha() { }
	void beta() { }
	void alpha2() { }
	void beta2() { }
}
