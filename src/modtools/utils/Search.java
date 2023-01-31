package modtools.utils;

import arc.func.Cons2;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import mindustry.gen.Icon;

public class Search {
	public Search() {}

	public Search(Cons2<Table, String> rebuild) {
		this.rebuild = rebuild;
	}
	public void build(Table title, Table cont) {
		title.table(top -> {
			top.image(Icon.zoom);
			TextField text = new TextField();
			top.add(text).fillX();
			text.changed(() -> {
				rebuild(cont, text.getText());
			});
		}).padRight(8.0f).fillX().top().row();
		rebuild(cont, null);
	}
	public Cons2<Table, String> rebuild;
	protected void rebuild(Table cont, String text) {
		if (rebuild != null) rebuild.get(cont, text);
	}
}