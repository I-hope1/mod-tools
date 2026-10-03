package test25;

/** V1：一个方法里一个闭包（孤立在 methodOld$ 组）。 */
public class XGroup {

	public void runR(Runnable r) { }

	public void methodOld() {
		runR(() -> shared());
	}

	void shared() { }
}
