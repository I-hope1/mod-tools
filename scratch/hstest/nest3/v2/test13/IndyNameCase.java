package test13;

import java.util.function.Supplier;

/** v2：只把两个承载方法的声明顺序对调。 */
public class IndyNameCase {

	public Runnable   r1;
	public Supplier<Object> r2;

	public void buildB() {
		r2 = () -> handleB();
	}

	public void buildA() {
		r1 = () -> handleA();
	}

	Object handleA() { return leafX(); }
	Object handleB() { return leafY(); }

	Object leafX() { return "X"; }
	Object leafY() { return "Y"; }
}
