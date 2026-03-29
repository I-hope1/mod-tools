package modtools.utils.search;

import arc.func.Cons2;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import modtools.utils.PatternUtils;

import java.util.regex.Pattern;

public class NavigatorSearch<E> extends Search<E> {
	public NavigatorSearch(TextField field, Cons2<Table, Pattern> rebuild) {
		super(rebuild);
		this.field = field;
	}
	public TextField fieldProvider() {
		return field;
	}
	public void build(Table title, Table cont) {
		field.changed(() -> rebuild(cont, PatternUtils.compileRegExpOrNull(field.getText())));
		rebuild(cont, PatternUtils.ANY);
	}
}
