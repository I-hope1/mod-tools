package test7;

/** v1：外层 lambda 体内，内层 lambda A 在前、B 在后。 */
public class SwapCase {

	public Runnable outer;

	public void build() {
		outer = () -> {
			Runnable a = () -> System.out.println("AAA");
			Runnable b = () -> System.out.println("BBB");
			a.run();
			b.run();
		};
	}
}
