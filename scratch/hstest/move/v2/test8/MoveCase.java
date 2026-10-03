package test8;

/**
 * v2：把 A 那段 lambda 从 build1 搬到 build2，同时删掉原来的 Z。
 *   build1 里已经没有 lambda
 *   build2 里是 lambda$build2$0  -> 方法体 A（内容没变，只是换了承载方法）
 *
 * 期望：
 *   - 方法体 A 应该认回旧名 lambda$build1$0（指纹一致）
 *   - 旧 lambda$build2$0 应该成为孤儿，被注入空壳
 *   - 持有旧 build2$0 的 CallSite 不能悄悄去跑 A 的代码
 */
public class MoveCase {

	public Runnable r1;
	public Runnable r2;

	public void build1() {
	}

	public void build2() {
		r2 = () -> System.out.println("AAA");
	}
}
