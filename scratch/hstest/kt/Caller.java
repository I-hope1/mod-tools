package test11;

/** 外部类：直接调用 Kt.getFoo$annotations()，模拟跨类引用。 */
public class Caller {

	public int call(Kt k) {
		return k.getFoo$annotations();
	}
}
