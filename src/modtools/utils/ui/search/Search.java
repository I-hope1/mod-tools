package modtools.utils.ui.search;

import arc.func.Cons2;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import mindustry.gen.Icon;

public class Search {
	public Search(Cons2<Table, String> rebuild) {
		this.rebuild = rebuild;
	}
	public void build(Table title, Table cont) {
		title.table(top -> {
			top.image(Icon.zoomSmall);
			TextField field = new TextField();
			field.setMessageText("@players.search");
			top.add(field).growX();
			field.changed(() -> rebuild(cont, field.getText()));
		}).padRight(8f).growX().top().row();
		rebuild(cont, null);
	}
	public Cons2<Table, String> rebuild;
	protected void rebuild(Table cont, String text) {
		if (rebuild != null) rebuild.get(cont, text);
	}
}