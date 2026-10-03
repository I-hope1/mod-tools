package test21;

/** V2：只删掉 A 链（第一次保存）。 */
public class Save3 {

	public void run(Runnable r) { }

	public void build() {
		run(() -> Time4.run(10, () -> Time4.run(5, () -> doB())));
	}

	void doA() { }
	void doB() { }
}
