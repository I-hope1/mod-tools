package test8;

/**
 * v1：两个承载方法各挂一个"同形无捕获"lambda。
 *   build1 里是 lambda$build1$0  -> 方法体 A
 *   build2 里是 lambda$build2$0  -> 方法体 Z
 */
public class MoveCase {

	public Runnable r1;
	public Runnable r2;

	public void build1() {
		r1 = () -> System.out.println("AAA");
	}

	public void build2() {
		r2 = () -> System.out.println("ZZZ");
	}
}
