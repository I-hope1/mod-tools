package test4;

/**
 * v2 的字面写法：最前面插入一个新按钮，原有三行源码一行未改。
 */
public class BtnCase {

	public Table t = new Table();

	public void build() {
		t.button("新建2", () -> createNew());
		t.button("新建", () -> create());
		t.button("保存", () -> save());
		t.button("删除", () -> delete());
	}

	void createNew() { }
	void create() { }
	void save()   { }
	void delete() { }
}
