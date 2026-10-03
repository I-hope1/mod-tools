import java.lang.reflect.*;

/**
 * 自检入口：只跑 SemAssert 的 selfCheck()，用于验证
 * "套件内断言失败 -> 失败计数 -> 进程非零退出" 整条链。
 *
 * 开关 HSTEST_SELFCHECK_EXPOSE 关闭时，自检的那次失败被抵消 -> 期望 exit 0；
 * 打开时保留那次失败 -> 期望 exit 1。后者才证明链是通的。
 */
public class SemAssertSelfCheck {
	public static void main(String[] a) throws Exception {
		Class<?> c = Class.forName("SemAssert");
		Method m = c.getDeclaredMethod("selfCheck");
		m.setAccessible(true);
		m.invoke(null);
		Field f = c.getDeclaredField("failed");
		f.setAccessible(true);
		int failed = f.getInt(null);
		boolean expose = "1".equals(System.getenv("HSTEST_SELFCHECK_EXPOSE"));
		System.out.println("自检后 failed=" + failed + "（开关" + (expose ? "开" : "关") + "）");
		System.exit(failed != 0 ? 1 : 0);
	}
}
