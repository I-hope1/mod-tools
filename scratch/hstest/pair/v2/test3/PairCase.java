package test3;

/**
 * v2：只把第一个 lambda 的方法体改掉（捕获列表、两个 lambda 的位置都没变）。
 * 期望：第一个 lambda 走"顺序回退"（方法体变了，指纹对不上），
 *       第二个 lambda 走"跨组指纹匹配"（方法体不变，指纹仍然吻合）。
 */
public class PairCase {

	public static class PaneContext {
		public Object        a;
		public Object        b;
		public StringBuilder sb = new StringBuilder();
	}

	public PaneContext ctx = new PaneContext();

	public void build() {
		Runnable r0 = () -> {
			System.out.println(ctx.sb + "0");
		};
		Runnable r1 = () -> {
			System.out.println(ctx.b + "1");
		};
		r0.run();
		r1.run();
	}
}
