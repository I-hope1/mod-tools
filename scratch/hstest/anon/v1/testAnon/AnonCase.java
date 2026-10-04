package testAnon;

public class AnonCase {
	void post(Runnable r) {
		if (r != null) r.run();
	}
	void doSave() {}
	void doDelete() {}

	public void setup() {
		Runnable save = () -> post(new Runnable() {
			@Override public void run() { doSave(); }
		});
		Runnable delete = () -> post(new Runnable() {
			@Override public void run() { doDelete(); }
		});
	}
}
