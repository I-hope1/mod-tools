package modtools.utils.search;

import arc.func.Boolf;
import arc.scene.Element;
import arc.scene.ui.layout.*;

public class TemplateTable<R> extends Table {
	private final FilterTable<R> template = new FilterTable<>();

	R NORMAL;
	public        Boolf<R> validator;
	public        boolean  noFilter;
	private final Runnable rebuild;
	public TemplateTable(R NORMAL, Boolf<R> boolf) {
		this.NORMAL = NORMAL;
		this.validator = boolf;

		update(rebuild = () -> {
			template.filter(p -> {
				if (noFilter || p == NORMAL) return true;
				return boolf.get(p);
			});
			/*var seq  = pane.getCells();
				int size = seq.size;
				for (int i = 0; i < size; i++) {
					if (seq.get(i).get() != null) continue;
					for (int j = i; j < size; j++) {
						if (seq.get(j).get() != null) {
							try {
								rowField.setInt(seq.get(j), rowField.getInt(seq.get(i)));
							} catch (Throwable ignored) {}
							seq.swap(i, j);
							break;
						}
					}
				}*/
		});
		act(0);
	}
	public void act(float delta) {
		super.act(delta);
		template.act(delta);
		if (!template.needsLayout()) return;
		template.layout();

		defaults().reset();
		super.clearChildren();

		var cells     = template.getCells();
		int slotIndex = 0; // 当前前台填充到的“槽位”下标

		for (int i = 0; i < cells.size; i++) {
			var     c  = cells.get(i);
			Element el = c.get();
			if (el == null) continue;

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
			} else {
				// 超出槽位数时的保底：使用自身样式
				newCell.set(c);
				if (c.isEndRow()) {
					super.row();
				}
			}
		}
		layout();
		invalidateHierarchy();
	}
	/** 添加用于切换是否显示所有的单选框 */
	public void addAllCheckbox(Table cont) {
		cont.check("No Filter", noFilter, b -> noFilter = b)
		 .tooltip("@mod-tools.tips.template.no_filter")
		 .growX();
	}
	public float getPrefWidth() {
		return template.getPrefWidth() + 12/* 好烦啊 */;
	}
	public Cell defaults() {
		return template.defaults();
	}
	public void updateNow() {
		rebuild.run();
	}

	public void clear() {
		super.clear();
		template.clear();
	}
	public <T extends Element> Cell<T> add(T element) {
		return template.add(element);
	}
	public Table row() {
		return template.row();
	}
	public void bind(R name) {
		template.bind(name);
	}
	public void unbind() {
		template.unbind();
	}
	public void newLine() {
		template.row();
	}
	public boolean isEmpty() {
		return template.isEmpty();
	}
}
