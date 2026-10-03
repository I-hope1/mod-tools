package test6;

/**
 * v2：在已有的内层 lambda 前面插入一个新的内层 lambda。
 * 外层 lambda 的源码结构没变，但内层序号整体位移。
 */
public class NestCase {

	public Runnable outer;

	public void build() {
		outer = () -> {
			Runnable added = () -> {
				System.out.println("lvl2-added");
			};
			added.run();
			Runnable inner = () -> {
				System.out.println("lvl2");
			};
			inner.run();
			System.out.println("lvl1");
		};
	}
}
