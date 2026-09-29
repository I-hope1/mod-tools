package modtools.utils.search;

import arc.func.*;
import arc.scene.Element;
import arc.scene.style.Drawable;
import arc.scene.ui.layout.Cell;
import arc.struct.Seq;
import arc.util.pooling.*;
import mindustry.ctype.UnlockableContent;
import modtools.ui.comp.limit.LimitTable;
import modtools.utils.PatternUtils;
import modtools.utils.ui.FormatHelper;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 用于通过绑定名称管理元素，并支持基于条件过滤和更新元素。
 * @param <E> 此表管理的元素类型。
 */
public class FilterTable<E> extends LimitTable {
	/** 用于绑定名称到其对应的 CellGroup 的映射。 */
	protected Map<E, CellGroup> map;

	/** 当前绑定的 CellGroup。 */
	private CellGroup current;
	private CellGroup nullGroup;

	/** 一个功能接口，用于处理元素。 */
	private Cons<Element> cons;

	public FilterTable() { }

	public FilterTable(Cons<? extends FilterTable<E>> cons) {
		super((Cons) cons);
	}

	/**
	 * 构造函数，接受背景和 Cons 类型的参数。
	 * @param background 背景对象。
	 * @param cons       用于初始化父类的 Cons 对象。
	 */
	@SuppressWarnings("rawtypes")
	public FilterTable(Drawable background, Cons<FilterTable<E>> cons) {
		super(background, (Cons) cons);
	}

	/**
	 * 绑定一个名称到一个新的或已存在的 CellGroup。
	 * @param name 要绑定的名称。
	 */
	public void bind(E name) {
		if (map == null) map = new HashMap<>();
		current = name == null ? nullGroup() : map.computeIfAbsent(name, _ -> new CellGroup());
	}
	private CellGroup nullGroup() {
		if (nullGroup == null) nullGroup = new CellGroup();
		return nullGroup;
	}
	public void rebind(E lastName, E newName) {
		if (map == null) map = new HashMap<>();
		if (lastName == null) {
			current = nullGroup();
			nullGroup = null; // 断开旧的 nullGroup 引用
		} else {
			current = map.computeIfAbsent(lastName, _ -> new CellGroup());
			map.remove(lastName);
		}
		map.put(newName, current);
	}

	/**
	 * 查找已绑定的 CellGroup。
	 * @param name 要查找的名称。
	 * @return 返回对应的 CellGroup。
	 */
	public CellGroup findBind(E name) {
		if (name == null) return nullGroup();
		if (map == null) map = new HashMap<>();
		return map.get(name);
	}

	/**
	 * 移除指定名称的 CellGroup。
	 * @param name 要移除的名称。
	 */
	public void removeCells(E name) {
		if (name == null) {
			nullGroup().removeElement();
			nullGroup = null;
			return;
		}
		if (map == null) map = new HashMap<>();
		if (map.containsKey(name)) {
			map.get(name).removeElement();
			map.remove(name);
		}
	}

	/**
	 * 设置添加元素的监听器。
	 * @param cons 处理元素的功能接口。
	 */
	public void listener(Cons<Element> cons) {
		this.cons = cons;
	}

	/**
	 * 清空表中的所有数据和状态。
	 * <p>顺序很重要：先 dispose 各 CellGroup（此时 cell 还没归还池），再
	 * {@code super.clear()} 把 cell 归还给 Table 的池。否则归还后再 clearElement
	 * 可能误清已经被别的表 obtain 走的 cell。
	 */
	public void clear() {
		unbind();
		if (map != null) {
			map.forEach((key, set) -> {
				// Cell 会被 Table 回收，不在这里回收
				if (!(key instanceof Cell) && key instanceof Pool.Poolable p) Pools.free(p);
				set.dispose();
			});
			map.clear();
			map = null;
		}
		super.clear();
		update(null);
		current = null;
		cons = null;
	}

	/**
	 * 判断当前是否已绑定。
	 * @return 如果当前有绑定则返回 true，否则返回 false。
	 */
	public boolean isBound() {
		return current != null;
	}

	/** 解绑当前绑定的 CellGroup。 */
	public void unbind() {
		current = null;
	}

	public <T extends Element> Cell<T> add(T element) {
		Cell<T> cell = super.add(element);
		bindCell(element, cell);
		return cell;
	}

	/**
	 * 绑定元素和 Cell。
	 * @param element 要绑定的元素。
	 * @param cell    要绑定的 Cell 对象。
	 * @param <T>     元素的类型。
	 */
	protected <T extends Element> void bindCell(T element, Cell<T> cell) {
		if (cons != null) cons.get(element);
		if (current != null) current.add(BindCell.of(cell));
	}

	/**
	 * 添加整数更新监听器。
	 * @param provider 提供整数值的提供者。
	 */
	public void setIntUpdateListener(Intp provider) {
		setConditionUpdateListener(new IntBoolf(provider, i -> (int) i));
	}

	/**
	 * 添加整数类型的更新监听器。
	 * @param provider 提供整数值的提供者。
	 */
	public void setUpdateListenerIntp(Intp provider) {
		setConditionUpdateListener(new IntBoolf(provider, i -> ((Intp) i).get()));
	}

	/**
	 * 添加模式更新监听器。
	 * @param provider 提供模式的提供者。
	 */
	public void setPatternUpdateListener(Prov<Pattern> provider) {
		setConditionUpdateListener(new PatternBoolf<>(provider));
	}

	/**
	 * 添加条件更新监听器。
	 * @param condition 更新条件。
	 */
	public void setConditionUpdateListener(Condition<E> condition) {
		update(() -> {
			if (condition.needUpdate()) filter(condition::valid);
		});
	}

	/**
	 * 过滤表中的元素。
	 * @param boolf 过滤条件。
	 */
	public void filter(Boolf<E> boolf) {
		if (map == null) return;
		map.forEach((name, seq) -> {
			seq.matched = boolf.get(name);
			applyGroup(seq);
		});
	}
	/** 只按缓存的结果 build / remove，不做任何匹配，不产生垃圾 */
	public void filterCached() {
		if (map == null) return;
		for (CellGroup g : map.values()) applyGroup(g);
	}

	private static void applyGroup(CellGroup g) {
		for (int i = 0; i < g.size; i++) {
			BindCell b = g.get(i);
			if (g.matched) {
				b.build();
			} else {
				b.remove();
			}
		}
	}

	/**
	 * 判断表是否为空。
	 * <p>手写循环，避免每次调用都分配 stream。
	 * @return 如果表为空则返回 true，否则返回 false。
	 */
	public boolean isEmpty() {
		if (map == null || map.isEmpty()) return true;
		for (CellGroup group : map.values()) {
			if (!group.removed) return false;
		}
		return true;
	}

	public int getMapSize() {
		return map == null ? 0 : map.size();
	}

	/**
	 * CellGroup 类，用于管理一组绑定的 Cell。
	 */
	public static class CellGroup extends Seq<BindCell> {

		/** 标记是否已被移除。 */
		public boolean removed = false;
		/** 最近一次过滤的结果 */
		public boolean matched = true;

		/** 大多数 CellGroup 只装 1 个 BindCell，默认容量 16 太浪费 */
		public CellGroup() {
			super(true, 1);
		}

		/**
		 * 移除当前 CellGroup 中的所有元素。
		 */
		public void removeElement() {
			each(BindCell::clear);
			clear();
			removed = true;
		}

		/**
		 * 清理当前 CellGroup 中的所有元素。
		 */
		public void dispose() {
			each(BindCell::clear);
			clear();
		}
	}

	/**
	 * IntBoolf 类实现 Condition 接口，用于处理整数更新条件。
	 */
	public class IntBoolf implements Condition<E> {

		/**
		 * 上次的整数值。
		 */
		int last;

		/**
		 * 整数值提供者。
		 */
		Intp provider;

		/**
		 * 整数转换函数。
		 */
		Intf<E> intf;

		/**
		 * 构造函数。
		 * @param provider 整数值提供者。
		 * @param intf     整数转换函数。
		 */
		public IntBoolf(Intp provider, Intf<E> intf) {
			this.provider = provider;
			this.intf = intf;
		}

		/**
		 * 判断是否需要更新。
		 * @return 如果需要更新则返回 true，否则返回 false。
		 */
		public boolean needUpdate() {
			return last != provider.get();
		}

		/**
		 * 判断元素是否有效。
		 * @param name 元素名称。
		 * @return 如果元素有效则返回 true，否则返回 false。
		 */
		public boolean valid(E name) {
			last = provider.get();
			return (last & intf.get(name)) != 0;
		}
	}

	/**
	 * PatternBoolf 类实现 Condition 接口，用于处理模式更新条件。
	 */
	public static class PatternBoolf<T> implements Condition<T>, Boolf<T> {
		Pattern       last;
		Prov<Pattern> provider;

		public PatternBoolf(Prov<Pattern> provider) {
			this.provider = provider;
		}

		public boolean needUpdate() {
			return last != provider.get();
		}

		/**
		 * 判断元素是否符合模式。
		 * @param name 元素名称。
		 * @return 如果元素符合模式则返回 true，否则返回 false。
		 */
		public boolean valid(T name) {
			Pattern pattern = provider.get();
			this.last = pattern;
			return switch (name) {
				case UnlockableContent u -> PatternUtils.test(pattern, u.localizedName)
				                            || PatternUtils.test(pattern, String.valueOf(u.name));
				case Drawable d -> PatternUtils.test(pattern, FormatHelper.getUIKeyOrNull(d));
				case String[] strings -> PatternUtils.test(pattern, strings[0]);
				case null, default -> PatternUtils.test(pattern, String.valueOf(name));
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