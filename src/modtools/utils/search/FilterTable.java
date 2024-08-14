package modtools.utils.search;

import arc.func.*;
import arc.scene.Element;
import arc.scene.style.Drawable;
import arc.scene.ui.layout.Cell;
import arc.struct.ObjectSet;
import arc.util.pooling.*;
import mindustry.ctype.UnlockableContent;
import modtools.ui.comp.limit.LimitTable;
import modtools.utils.PatternUtils;
import modtools.utils.ui.FormatHelper;

import java.util.*;
import java.util.regex.Pattern;

public class FilterTable<E> extends LimitTable {
	public FilterTable() { }
	@SuppressWarnings("rawtypes")
	public FilterTable(Cons<FilterTable<E>> cons) {
		super((Cons) cons);
	}
	@SuppressWarnings("rawtypes")
	public FilterTable(Drawable background, Cons<FilterTable<E>> cons) {
		super(background, (Cons) cons);
	}
	protected Map<E, CellGroup> map;
	private   CellGroup         current;
	private   Cons<Element>     cons;

	public void bind(E name) {
		if (map == null) map = new HashMap<>();
		current = map.computeIfAbsent(name, _ -> new CellGroup());
	}
	public CellGroup getCurrent() {
		return current;
	}
	/** 对添加的元素进行在此操作  */
	public void listener(Cons<Element> cons) {
		this.cons = cons;
	}
	public void clear() {
		super.clear();
		unbind();
		if (map != null) {
			map.forEach((key, set) -> {
				// Cell 会自动被table回收
				if (!(key instanceof Cell) && key instanceof Pool.Poolable p) Pools.free(p);
				set.dispose();
			});
			map.clear();
			map = null;
		}
		update(null);
		current = null;
		cons = null;
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
		if (current != null) current.add(BindCell.of(cell));
	}

	public void addIntUpdateListener(Intp provider) {
		addConditionUpdateListener(new IntBoolf(provider, i -> (int) i));
	}
	/* 就是E == Intp */
	public void addUpdateListenerIntp(Intp provider) {
		addConditionUpdateListener(new IntBoolf(provider, i -> ((Intp) i).get()));
	}
	public void addPatternUpdateListener(Prov<Pattern> provider) {
		addConditionUpdateListener(new PatternBoolf<>(provider));
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
		return map == null || map.isEmpty() || map.entrySet().stream().anyMatch(entry -> !entry.getValue().removed);
	}

	public static class CellGroup extends ObjectSet<BindCell> {
		public boolean removed = false;
		public void removeElement() {
			each(BindCell::clear);
			clear();
			removed = true;
		}
		public void dispose() {
			each(BindCell::clear);
			clear();
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

	public static class PatternBoolf<T> implements Condition<T>, Boolf<T> {
		Pattern       last;
		Prov<Pattern> provider;
		public PatternBoolf(Prov<Pattern> provider) {
			this.provider = provider;
		}
		public boolean needUpdate() {
			return last != provider.get();
		}
		public boolean valid(T name) {
			return switch (name) {
				case UnlockableContent u -> PatternUtils.test(last = provider.get(), u.localizedName)
				                            || PatternUtils.test(last, String.valueOf(u.name));
				case Drawable d -> PatternUtils.test(last = provider.get(), FormatHelper.getUIKeyOrNull(d));
				case String[] strings -> PatternUtils.test(last = provider.get(), strings[0]);
				case null, default -> PatternUtils.test(last = provider.get(), String.valueOf(name));
			};
		}
		public boolean get(T object) {
			return valid(object);
		}
	}


	public interface Condition<E> {
		default boolean needUpdate() { return true; }
		boolean valid(E name);
	}
}
