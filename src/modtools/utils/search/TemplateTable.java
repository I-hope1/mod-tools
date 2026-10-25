package modtools.utils.search;

import arc.func.Boolf;
import arc.scene.Element;
import arc.scene.ui.layout.*;
import arc.struct.Seq;

/**
 * 前台显示「模板里被过滤条件保留的元素」，模板只负责槽位结构。
 * <p>模板与本表共用同一批元素，一个元素只能挂在一个父节点上，所以「谁该出现在哪个槽位」
 * 必须由本表自己记（{@link #order} / {@link #names}），不能回头读模板的 cell。
 */
public class TemplateTable<R> extends Table {
	/**
	 * 只存「槽位定义」：每个 cell 的 pad / size / fill / colspan / endRow。
	 * <p>元素被搬进本表时，{@code Table.removeChild} 会把对应 cell 的 element 置空
	 * （{@code Table.java:605-610}），所以模板记不住「哪个槽位该放谁」。
	 * <p>这里刻意用 {@link Table} 而不是 {@link FilterTable}：过滤由本表按 {@link #names}
	 * 自己求值，模板不参与，两边的状态不会互相回写。
	 */
	private final Table template = new Table();

	R NORMAL;
	public        Boolf<R> validator;
	public        boolean  noFilter;
	private final Runnable rebuild;
	private       boolean  evalDirty = true;
	private       boolean  lastNoFilter;
	/**
	 * 前台 cells 需要按模板重新排一遍。
	 * <p>不能拿 {@code template.needsLayout()} 代替：重建要把元素搬进本表，而
	 * {@code Group.addChild} / {@code Cell.setElement} 都会触发旧父节点的
	 * {@code childrenChanged()} → {@code invalidateHierarchy()}，搬元素这个动作本身
	 * 就会把模板置脏，标记于是恒为真。脏只能来自「可见集合真的变了」和
	 * 「模板结构真的变了」（{@link #add} / {@link #row} / {@link #clear}）。
	 */
	private       boolean  dirty     = true;

	/**
	 * 模板里的元素，按槽位顺序（= add 顺序）排列，与 {@link #names} 一一对应。
	 */
	private final Seq<Element> order = new Seq<>();
	/** 与 {@link #order} 一一对应的绑定名，用来查当前匹配状态 */
	private final Seq<R>       names = new Seq<>();
	/**
	 * 上一次重建时每个槽位是否可见，与 {@link #order} 同下标。
	 * <p>存在的理由是「{@code re} 为真」不等于「可见集合变了」：{@link FilterTable.Condition#needUpdate()}
	 * 可以长期为真（{@code noFilter} 打开时 {@code internalFilter} 会短路，
	 * {@code PatternBoolf.valid} 根本不会被调用，它的 {@code last} 就永远追不上 provider），
	 * 直接拿 {@code re} 当重建信号会让重建每帧都跑。
	 */
	private final Seq<Boolean> built = new Seq<>();
	/** {@link #bind} 设的当前名字，{@link #add} 时记进 {@link #names} */
	private       R            currentName;

	private Runnable outerUpdater;

	private final Boolf<R> internalFilter = p -> noFilter || p == NORMAL || (validator != null && validator.get(p));
	public TemplateTable(R NORMAL, Boolf<R> boolf) {
		this.NORMAL = NORMAL;
		this.validator = boolf;

		super.update(rebuild = () -> {
			if (outerUpdater != null) outerUpdater.run();
			boolean re = evalDirty || noFilter != lastNoFilter
			             || (validator instanceof FilterTable.Condition<?> c && c.needUpdate());
			// if (!needFilter()) return;
			if (!re) return;
			evalDirty = false;
			lastNoFilter = noFilter;
			// 只在可见集合真的翻转时才置脏。这一步顺带把 Condition 的 last 追平，
			// 免得 needUpdate() 卡在 true 上（见 built 的注释）。
			if (syncVisible()) dirty = true;
		});
		act(0);
	}

	/** 按当前条件重算每个槽位的可见性；返回可见集合是否真的变了 */
	private boolean syncVisible() {
		boolean changed = false;
		for (int i = 0; i < order.size; i++) {
			boolean now = matches(names.get(i));
			if (i < built.size) {
				if (built.get(i) != now) {
					built.set(i, now);
					changed = true;
				}
			} else {
				built.add(now);
				changed = true;
			}
		}
		return changed;
	}
	private boolean matches(R name) {
		// 没有 bind 就 add 的元素（name == null）一律保留：没有名字就没有可查的匹配结果
		return name == null || internalFilter.get(name);
	}

	@Override
	public Element update(Runnable r) {
		outerUpdater = r;
		return this;
	}
	@Override
	public void act(float delta) {
		super.act(delta);
		// 首帧元素还挂在模板上（一次都还没搬过），不替它 act 就漏一帧；
		// 之后元素常驻本表，这里只是遍历一张空表。
		template.act(delta);
		if (!dirty) return;
		dirty = false;

		super.defaults().reset();
		// 前台清空：先解绑父指针，Table.clearChildren 里逐个 actor.remove() 就会短路
		// （Element.remove 是 `parent != null && ...`，Element.java:200），从 O(K²) 降到 O(K)。
		// 这不会跳过任何收尾动作：unfocus / setScene(null) 在 Group.clearChildren 里，
		// 而 Table.clearChildren 末尾一定会调它（Table.java:629）；cell 的 element
		// 由 cellPool.freeAll → Cell.reset() 清掉（Cell.java:685）。
		detachAll(this);
		super.clearChildren();

		// 模板只保留槽位定义：解绑 + 清空 children + 逐 cell 置空 element。
		// 少了这一步，没被保留的元素会一直挂在模板的 cells 里，算进 template.getPrefWidth()。
		detachAll(template);
		template.getChildren().clear();
		var cells = template.getCells();
		for (int i = 0; i < cells.size; i++) cells.get(i).clearElement();

		int slotIndex = 0; // 当前前台填充到的“槽位”下标

		for (int i = 0; i < order.size; i++) {
			if (!built.get(i)) continue;
			Element el = order.get(i);

			// 添加可见元素
			Cell<?> newCell = super.add(el);

			// 越界安全检查：防止可见元素数量超过模板总槽位数
			if (slotIndex < cells.size) {
				// 获取当前槽位的原始配置
				Cell<?> slotCell = cells.get(slotIndex);

				// 复制当前槽位的排版约束（pad, size, fill等），实现无缝紧凑补位
				newCell.set(slotCell);

				// 如果当前槽位原本是行末，则换行！
				if (slotCell.isEndRow()) {
					super.row();
				}
				slotIndex++;
			} else if (i < cells.size) {
				// 超出槽位数时的保底：使用自身样式
				Cell<?> own = cells.get(i);
				newCell.set(own);
				if (own.isEndRow()) {
					super.row();
				}
			}
		}
		layout();
		invalidateHierarchy();
	}
	/**
	 * 把 {@code t} 的所有子元素的父指针置空，**不动** children 列表本身。
	 * <p>只做这一件事，就能让后面所有「按元素移除」的路径变成 O(1)：{@code Element.remove()}
	 * 是 {@code parent != null && parent.removeChild(...)}，父指针为 null 时直接返回 false，
	 * 于是 {@code Table.removeChild} 里的 {@code getCell} 线性扫描和
	 * {@code childrenChanged() → invalidateHierarchy()} 都不会发生。
	 * <p>代价是 {@code setScene(null)} / {@code unfocus} 也不发生，所以**调用方必须**接着
	 * 走一条会补上它们的路径：{@code Group.clearChildren()} 里就有（{@code Group.java:341-353}）。
	 */
	private static void detachAll(Table t) {
		var       children = t.getChildren();
		Element[] items    = children.begin();
		for (int i = 0, n = children.size; i < n; i++) items[i].parent = null;
		children.end();
	}
	/** 添加用于切换是否显示所有的单选框 */
	public void addAllCheckbox(Table cont) {
		cont.check("No Filter", noFilter, b -> noFilter = b)
		 .tooltip("@mod-tools.tips.template.no_filter")
		 .growX();
	}
	@Override
	public float getPrefWidth() {
		// 可见内容在本表的 cells 里：模板的 cells 在搬元素时被置空，量不出内容宽。
		// 但重建之前本表还是空的、值都在模板那边，所以两边取大。少了模板这一支，首次测量会算成 0。
		return Math.max(super.getPrefWidth(), template.getPrefWidth());
	}
	@Override
	public Cell defaults() {
		return template.defaults();
	}
	public void updateNow() {
		evalDirty = true;
		rebuild.run();
	}

	@Override
	public void clear() {
		super.clear();
		template.clear();
		order.clear();
		names.clear();
		built.clear();
		currentName = null;
		dirty = true;
	}
	@Override
	public <T extends Element> Cell<T> add(T element) {
		evalDirty = true;
		order.add(element);
		names.add(currentName);
		return template.add(element);
	}
	@Override
	public Table row() {
		dirty = true;
		return template.row();
	}
	public void bind(R name) {
		currentName = name;
	}
	public void unbind() {
		currentName = null;
	}
	public void newLine() {
		dirty = true;
		template.row();
	}
	public boolean isEmpty() {
		return order.size == 0;
	}
}
