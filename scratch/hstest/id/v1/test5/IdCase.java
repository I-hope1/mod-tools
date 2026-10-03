package test5;

/**
 * 三个"完全同形且同体"的 lambda：都只捕获 this、都调 create()、无局部变量。
 * 字节码层面三者逐字节等价 —— 这是用户反例的最强形态。
 */
public class IdCase {

	public Table t = new Table();

	public void build() {
		t.button("新建", () -> create());
		t.button("保存", () -> create());
		t.button("删除", () -> create());
	}

	void create() { }
}
