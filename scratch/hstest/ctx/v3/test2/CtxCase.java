package test2;

/**
 * v3：不插入新 lambda，只是把方法体改了（v1 的 lambda 访问 ctx.sb，
 * v3 的同一个 lambda 改成访问 ctx.infoCell）。
 */
public class CtxCase {

	public static class PaneContext {
		public Object        pane;
		public Object        infoCell;
		public StringBuilder sb = new StringBuilder();
	}

	public PaneContext ctx = new PaneContext();

	public void build() {
		ctx.pane = (Runnable) () -> {
			System.out.println(ctx.infoCell);
		};
		ctx.infoCell = (Runnable) () -> {
			System.out.println(ctx.infoCell);
		};
	}
}
