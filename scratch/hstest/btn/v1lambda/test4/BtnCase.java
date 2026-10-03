package test4;

/** v1 的字面写法：写成显式 lambda 而不是方法引用，看是否等价。 */
public class BtnCase {

	public Table t = new Table();

	public void build() {
		t.button("新建", () -> create());
		t.button("保存", () -> save());
		t.button("删除", () -> delete());
	}

	void create() { }
	void save()   { }
	void delete() { }
}
