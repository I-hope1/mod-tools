package test9;

/**
 * 关键形态：实例 lambda，且它自己的首个显式参数恰好是本类类型。
 *   observe(other) 里的 lambda 同时：
 *     - 捕获 this（访问 this.y）  -> 逼 forceStaticLambdas 做 static 转换
 *     - 形参是 Foo                 -> 合成方法描述符变成 (Ltest9/Foo;)V
 * 于是"强转后"与"本来就长这样"两种形态无法用描述符区分。
 */
public class Foo {

	public int x;
	public int y;

	public void run(Runnable r) { r.run(); }

	public void observe(Foo other) {
		run(() -> System.out.println(this.y + other.x));
	}
}
