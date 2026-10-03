package test12;

/** v2：两个承载方法的**声明顺序对调**（内容一字未改），看外层会不会对调。 */
public class Nest2Case {

	public Runnable r1;
	public Runnable r2;

	public void buildB() {
		r2 = () -> handleB();
	}

	public void buildA() {
		r1 = () -> handleA();
	}

	void handleA() { leafX(); }
	void handleB() { leafY(); }

	void leafX() { System.out.println("X"); }
	void leafY() { System.out.println("Y"); }
}
