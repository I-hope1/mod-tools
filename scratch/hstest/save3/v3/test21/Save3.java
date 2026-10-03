package test21;

/** V3：再改 B 链的叶子体（第二次保存）。此时类里已经有 V2 留下的幽灵。 */
public class Save3 {

	public void run(Runnable r) { }

	public void build() {
		run(() -> Time4.run(10, () -> Time4.run(5, () -> doB2())));
	}

	void doA() { }
	void doB() { }
	void doB2() { }
}
