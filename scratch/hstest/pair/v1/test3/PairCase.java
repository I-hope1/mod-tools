package test3;

/**
 * v1：同一方法内两个"同形"lambda（同一描述符），方法体不同。
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
			System.out.println(ctx.a + "0");
		};
		Runnable r1 = () -> {
			System.out.println(ctx.b + "1");
		};
		r0.run();
		r1.run();
	}
}
