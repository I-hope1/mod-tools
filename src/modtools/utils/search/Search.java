package modtools.utils.search;

import arc.Core;
import arc.func.Cons2;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import mindustry.gen.Icon;
import modtools.utils.PatternUtils;

import java.util.regex.Pattern;

public class Search {
	public Search(Cons2<Table, Pattern> rebuild) {
		this.rebuild = rebuild;
	}
	public void build(Table title, Table cont) {
		title.table(top -> {
			top.image(Icon.zoomSmall);
			TextField field = new TextField();
			field.setMessageText("@players.search");
			top.add(field).growX();
			field.changed(() -> {
				rebuild(cont, PatternUtils.compileRegExpOrNull(field.getText()));
			});
		}).padRight(8f).growX().top().row();
		Core.app.post(() -> rebuild(cont, PatternUtils.ANY));
	}
	public final Cons2<Table, Pattern> rebuild;
	protected void rebuild(Table cont, Pattern pattern) {
		if (rebuild != null) rebuild.get(cont, pattern);
	}
}