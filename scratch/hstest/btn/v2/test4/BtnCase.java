package test4;

/**
 * v2：在最前面插入一个"新建"按钮（新 lambda），原有三个按钮源码一行未改。
 * 注意 v1 里第一个按钮本来就叫"新建"，这里故意再插一个同名标签的新按钮，
 * 模拟用户说的"新插入"。
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
