package modtools.utils.ui.search;

import arc.func.*;
import arc.scene.Element;
import arc.scene.style.Drawable;
import arc.scene.ui.layout.Cell;
import arc.struct.ObjectSet;
import arc.util.pooling.Pools;
import modtools.ui.components.limit.LimitTable;
import modtools.utils.PatternUtils;

import java.util.*;
import java.util.regex.Pattern;

public class FilterTable<E> extends LimitTable {
	public FilterTable() {}
	@SuppressWarnings("rawtypes")
	public FilterTable(Cons<FilterTable<E>> cons) {
		super((Cons) cons);
	}
	@SuppressWarnings("rawtypes")
	public FilterTable(Drawable background, Cons<FilterTable<E>> cons) {
		super(background, (Cons) cons);
	}
	protected Map<E, ObjectSet<BindCell>> map;
	ObjectSet<BindCell> current;

	private Cons<Element> cons;

	public void bind(E name) {
		if (map == null) map = new HashMap<>();
		current = map.computeIfAbsent(name, k -> new ObjectSet<>());
	}
	public void listener(Cons<Element> cons) {
		this.cons = cons;
	}
	public void clear() {
		super.clear();
		unbind();
		if (map != null) {
			map.forEach((key, seq) -> {
				if (key instanceof Compound<?, ?> compound) Pools.free(compound);
				seq.each(BindCell::clear);
			});
			map.clear();
		}
	}
	public boolean isBound() {
		return current != null;
	}
	public void unbind() {
		current = null;
	}

	public <T extends Element> Cell<T> add(T element) {
		Cell<T> cell = super.add(element);
		bindCell(element, cell);
		return cell;
	}
	protected <T extends Element> void bindCell(T element, Cell<T> cell) {
		if (cons != null) cons.get(element);
		if (current != null) current.add(new BindCell(cell));
	}

	public void addIntUpdateListener(Intp provider) {
		addConditionUpdateListener(new IntBoolf(provider, i -> (int) i));
	}
	/* 就是E == Intp */
	public void addIntp_UpdateListener(Intp provider) {
		addConditionUpdateListener(new IntBoolf(provider, i -> ((Intp) i).get()));
	}
	public void addPatternUpdateListener(Prov<Pattern> provider) {
		addConditionUpdateListener(new PatternBoolf(provider));
	}
	public void addConditionUpdateListener(Condition<E> condition) {
		update(() -> {
			if (condition.needUpdate()) filter(condition::valid);
		});
	}


	// public Table unuseTable = new Table();
	public void filter(Boolf<E> boolf) {
		if (map == null) return;
		map.forEach((name, seq) -> {
			seq.each(boolf.get(name) ? BindCell::build : BindCell::remove);
		});
	}
	public boolean isEmpty() {
		return map == null || map.isEmpty();
	}

	public static final class Compound<P1, P2> {
		final P1 p1;
		final P2 p2;
		private Compound(P1 p1, P2 p2) {
			this.p1 = p1;
			this.p2 = p2;
		}
		public static <P1, P2> Compound<P1, P2> with(P1 p1, P2 p2) {
			return Pools.get(Compound.class, () -> new Compound<>(p1, p2)).obtain();
		}
	}

	public class IntBoolf implements Condition<E> {
		int     last;
		Intp    provider;
		Intf<E> intf;
		public IntBoolf(Intp provider, Intf<E> intf) {
			this.provider = provider;
			this.intf = intf;
		}
		public boolean needUpdate() {
			return last != provider.get();
		}
		public boolean valid(E name) {
			last = provider.get();
			return (last & intf.get(name)) != 0;
		}
	}
	public class PatternBoolf implements Condition<E> {
		Pattern       last;
		Prov<Pattern> provider;
		public PatternBoolf(Prov<Pattern> provider) {
			this.provider = provider;
		}
		public boolean needUpdate() {
			return last != provider.get();
		}
		public boolean valid(E name) {
			return PatternUtils.test(last = provider.get(), String.valueOf(name));
		}
	}


	public interface Condition<E> {
		default boolean needUpdate() {return true;}
		boolean valid(E name);
	}
}
