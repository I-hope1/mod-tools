package test19;

/**
 * 两级链：外层的体只是求值一个中层 lambda。
 * 用来检验 Step 2（只跑一次）里 hasUnmatchedChild 的时序问题：
 * 若外层先于中层被处理，而中层的体又变了（指纹/语义都对不上），
 * 外层会被护栏跳过，而 Step 2 不会再来第二遍。
 */
public class TwoLevel {

	public void run(Runnable r) { }

	public void build() {
		run(() -> Time2.run(10, () -> doX()));
	}

	void doX() { }
	void doY() { }
}
