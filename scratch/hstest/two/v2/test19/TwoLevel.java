package test19;

/**
 * v2：在开头插入一个新的普通 lambda（让 build 里的 lambda 序号整体位移），
 * 同时把原链的叶子方法体改掉 —— 于是外层与中层都无法靠指纹/语义认领，
 * 两级都只能落到 Step 2。
 */
public class TwoLevel {

	public void run(Runnable r) { }

	public void build() {
		run(() -> noop());
		run(() -> Time2.run(10, () -> doY()));
	}

	void noop() { }
	void doX() { }
	void doY() { }
}
