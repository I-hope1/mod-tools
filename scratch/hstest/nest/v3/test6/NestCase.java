package test6;

/** v3：嵌套结构不动，只把内层 lambda 的方法体改掉。 */
public class NestCase {

	public Runnable outer;

	public void build() {
		outer = () -> {
			Runnable inner = () -> {
				System.out.println("lvl2-CHANGED");
			};
			inner.run();
			System.out.println("lvl1");
		};
	}
}
