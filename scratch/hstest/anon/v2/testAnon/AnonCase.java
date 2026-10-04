package testAnon;

public class AnonCase {
	void post(Runnable r) {
		if (r != null) r.run();
	}
	void doSave() {}
	void doDelete() {}
	void doOther() {}

	public void setup() {
		Runnable other = () -> doOther(); // 在 Save 和 Delete 前面插入新 lambda
		Runnable save = () -> post(new Runnable() {
			@Override public void run() { doSave(); }
		});
		Runnable delete = () -> post(new Runnable() {
			@Override public void run() { doDelete(); }
		});
	}
}
