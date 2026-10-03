package test4;

/**
 * v1：三个按钮。注意三个 lambda 体都是"调用本类另一个方法"，
 * 只有被调方法名不同（create/save/delete）。
 */
public class BtnCase {

	public Table t = new Table();

	public void build() {
		t.button("新建", this::create);
		t.button("保存", this::save);
		t.button("删除", this::delete);
	}

	void create() { }
	void save()   { }
	void delete() { }
}
