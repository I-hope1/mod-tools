package modtools.utils.search;

import arc.func.Cons2;
import arc.scene.ui.layout.Table;

import java.util.regex.Pattern;

public class NavigatorSearch<E> extends Search<E> {
	public NavigatorSearch(Cons2<Table, Pattern> rebuild) {
		super(rebuild);
	}
	public void build(Table title, Table cont) {
		super.build(title, cont);
	}
}
