package test7;

/**
 * v2：只把两个内层 lambda 的声明顺序对调（A/B 互换）。
 * 外层 lambda 的指令结构完全不变（仍是 2 个 indy、同样的描述符），
 * 变化的只有内层 lambda 各自的名字（$1/$2 互换）。
 */
public class SwapCase {

	public Runnable outer;

	public void build() {
		outer = () -> {
			Runnable b = () -> System.out.println("BBB");
			Runnable a = () -> System.out.println("AAA");
			a.run();
			b.run();
		};
	}
}
