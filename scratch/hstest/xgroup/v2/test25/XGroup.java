package test25;

/**
 * V2：methodOld 被拆成 methodA / methodB，两者体内**各自**放一个完全相同的闭包。
 * 于是两个新 lambda 分属 methodA$ / methodB$ 两个不同组，却与老类里孤立的老 lambda
 * 拥有相同指纹 —— 这就是跨组争抢（Cross-Group Contention）。
 */
public class XGroup {

	public void runR(Runnable r) { }

	public void methodA() {
		runR(() -> shared());
	}

	public void methodB() {
		runR(() -> shared());
	}

	void shared() { }
}
