package test6;

/** 三层嵌套 lambda：外层 lambda 体内再定义 lambda。 */
public class NestCase {

	public Runnable outer;

	public void build() {
		outer = () -> {
			Runnable inner = () -> {
				System.out.println("lvl2");
			};
			inner.run();
			System.out.println("lvl1");
		};
	}
}
