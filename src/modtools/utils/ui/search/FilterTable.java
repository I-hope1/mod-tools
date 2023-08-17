package modtools.utils.ui.search;

import arc.func.*;
import arc.scene.Element;
import arc.scene.style.Drawable;
import arc.scene.ui.layout.Cell;
import arc.struct.*;
import modtools.ui.components.limit.LimitTable;
import modtools.utils.PatternUtils;

import java.util.function.Supplier;
import java.util.regex.Pattern;

@SuppressWarnings("rawtypes")
public class FilterTable<E> extends LimitTable {
	public FilterTable() {}
	public FilterTable(Cons<FilterTable<E>> cons) {
		super((Cons) cons);
	}
	public FilterTable(Drawable background, Cons<FilterTable<E>> cons) {
		super(background, (Cons) cons);
	}
	ObjectMap<E, Seq<BindCell>> map;
	private Seq<BindCell> current;
	private Cons<Element> cons;


	public void bind(E name) {
		if (map == null) map = new ObjectMap<>();
		current = map.get(name, Seq::new);
	}
	public void listener(Cons<Element> cons) {
		this.cons = cons;
	}
	public void clear() {
		super.clear();
		if (map != null) {
			map.each((__, seq) -> {
				seq.each(BindCell::clear);
				seq.clear().shrink();
			});
			map.clear();
		}
		unbind();
	}
	public void unbind() {
		current = null;
	}

	public <T extends Element> Cell<T> add(T element) {
		Cell<T> cell = super.add(element);
		if (cons != null) cons.get(element);
		if (current != null) current.add(new BindCell(cell));
		return cell;
	}

	public void addIntUpdateListener(Intp supplier) {
		int[] last = {0};
		update(() -> {
			if (last[0] != supplier.get()) {
				last[0] = supplier.get();
				filter(name -> (last[0] & (int) name) != 0);
			}
		});
	}

	public void addUpdateListener(Supplier<Pattern> supplier) {
		Pattern[] last = {null};
		update(() -> {
			if (last[0] != supplier.get()) {
				last[0] = supplier.get();
				filter(name -> PatternUtils.test(last[0], String.valueOf(name)));
			}
		});
	}

	// public Table unuseTable = new Table();
	public void filter(Boolf<E> boolf) {
		if (map == null) return;
		map.each((name, seq) -> {
			seq.each(boolf.get(name) ?
			 BindCell::build : BindCell::remove);
		});
	}
	public boolean isEmpty() {
		return map == null || map.isEmpty();
	}
}
