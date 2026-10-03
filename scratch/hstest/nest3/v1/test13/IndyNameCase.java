package test13;

import java.util.function.Supplier;

/**
 * 让两个外层 lambda 的 body 完全同形，唯一差别是它们实现的函数式接口不同
 * （Runnable.run vs Supplier.get），即 indy 名字不同。
 * 内层 handler 名字都会被 #SYNTHETIC_METHOD# 屏蔽掉。
 */
public class IndyNameCase {

	public Runnable   r1;
	public Supplier<Object> r2;

	public void buildA() {
		r1 = () -> handleA();
	}

	public void buildB() {
		r2 = () -> handleB();
	}

	Object handleA() { return leafX(); }
	Object handleB() { return leafY(); }

	Object leafX() { return "X"; }
	Object leafY() { return "Y"; }
}
