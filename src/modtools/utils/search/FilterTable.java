package modtools.utils.search;

import arc.func.*;
import arc.graphics.Color;
import arc.scene.Element;
import arc.scene.style.Drawable;
import arc.scene.ui.layout.Cell;
import arc.struct.*;
import arc.util.Align;
import modtools.ui.IntStyles;
import modtools.ui.components.input.MyLabel;
import modtools.ui.components.limit.LimitTable;
import modtools.utils.JSFunc.*;
import modtools.utils.Tools;

import java.util.function.Supplier;
import java.util.regex.Pattern;

public class FilterTable extends LimitTable {
	public FilterTable() {}
	public FilterTable(Cons<FilterTable> cons) {
		super((Cons) cons);
	}
	public FilterTable(Drawable background, Cons<FilterTable> cons) {
		super(background, (Cons) cons);
	}
	ObjectMap<String, Seq<BindCell>> map;
	private Seq<BindCell> current;

	public void bind(String name) {
		if (map == null) map = new ObjectMap<>();
		current = map.get(name, Seq::new);
	}

	public void unbind() {
		current = null;
	}

	public <T extends Element> Cell<T> add(T element) {
		Cell<T> cell = super.add(element);
		if (current != null) current.add(new BindCell(cell));
		return cell;
	}

	public void addUpdateListener(Supplier<Pattern> supplier) {
		Pattern[] last = {null};
		update(() -> {
			if (last[0] != supplier.get()) {
				last[0] = supplier.get();
				filter(name -> Tools.test(last[0], name));
			}
		});
	}

	// public Table unuseTable = new Table();

	public void filter(Boolf<String> boolf) {
		map.each((name, seq) -> {
			seq.each(boolf.get(name) ?
					         BindCell::build : BindCell::remove);
		});
	}
}
