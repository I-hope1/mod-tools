package test11;

/**
 * 模拟 Kotlin 的 foo$default / getX$annotations 这类"名字带 $、体相同、被外部类调用"的
 * 合成方法。两个方法体完全一样（都返回 0），逻辑名不同。
 */
public class Kt {

	public int getFoo$annotations() { return 0; }

	public int getBar$annotations() { return 0; }
}
