package test2;

/**
 * v2：在 build() 的第一行插入一个全新的 lambda（新增监听器），
 * 原有两个 lambda 的源码位置、方法体、捕获列表全部没变。
 */
public class CtxCase {

	public static class PaneContext {
		public Object        pane;
		public Object        infoCell;
		public StringBuilder sb = new StringBuilder();
	}

	public PaneContext ctx = new PaneContext();

	public void build() {
		// 新增的第三个 lambda（放在前面）
		ctx.sb.append("watch");
		Runnable added = () -> {
			System.out.println("added");
		};
		added.run();

		ctx.pane = (Runnable) () -> {
			System.out.println(ctx.sb);
		};
		ctx.infoCell = (Runnable) () -> {
			System.out.println(ctx.infoCell);
		};
	}
}
