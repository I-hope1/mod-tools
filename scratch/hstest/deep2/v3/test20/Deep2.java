package test20;

/**
 * V2 变体乙：删掉 doA 整条链，同时把 doB 链的叶子改成 doB2。
 * —— 编号整体位移，且叶子体已变，没有任何指纹/语义证据可用。
 * 预期（推演）：doB2 整条链顶到 doA 的旧名上，老 doB 调用点被熔断。
 * 这是一个"已知限制"用例，用来把行为固定下来。
 */
public class Deep2 {

	public void run(Runnable r) { }

	public void build() {
		run(() -> Time3.run(10, () -> Time3.run(5, () -> doB2())));
	}

	void doA() { }
	void doB() { }
	void doB2() { }
}
