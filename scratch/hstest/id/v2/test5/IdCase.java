package test5;

/** v2：最前面插入一个新按钮，原有三行一字未改。 */
public class IdCase {

	public Table t = new Table();

	public void build() {
		t.button("新增", () -> create());
		t.button("新建", () -> create());
		t.button("保存", () -> create());
		t.button("删除", () -> create());
	}

	void create() { }
}
