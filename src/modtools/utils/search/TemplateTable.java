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
		template.invalidate();
		super.clearChildren();

		var cells = template.getCells();
		for (int i = 0; i < cells.size; i++) {
			var c = cells.get(i);
			if (c.get() == null) continue;
			super.add(c.get()).set(c);
			if (cells.get(getChildren().size - 1).isEndRow()) super.row();
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
