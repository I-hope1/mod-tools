package test2;

/**
 * 提案形态 v1：两个 lambda，各自只捕获一个 Context 引用。
 * 注意两者逻辑名相同（都在 build 里）、归一化描述符也相同。
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
			System.out.println(ctx.sb);
		};
		ctx.infoCell = (Runnable) () -> {
			System.out.println(ctx.infoCell);
		};
	}
}
