package modtools.ui.comp.utils;

import arc.func.Boolf;
import arc.scene.Element;
import arc.scene.ui.layout.*;
import modtools.utils.ui.search.FilterTable;

public class TemplateTable<R> extends Table {
	FilterTable<R> template = new FilterTable<>();

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
			super.clearChildren();
			var cells = template.getCells();
			for (int i = 0; i < cells.size; i++) {
				var c = cells.get(i);
				if (c.get() == null) continue;
				super.add(c.get()).set(c);
				if (cells.get(getChildren().size - 1).isEndRow()) super.row();
			}
			// Log.info(cells);
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
	}
	/** 添加用于切换是否显示所有的单选框 */
	public void addAllCheckbox(Table cont) {
		cont.check("No Filter", noFilter, b -> noFilter = b)
		 .tooltip("@mod-tools.tips.template.no_filter")
		 .growX();
	}

	public void updateNow() {
		rebuild.run();
	}

	public void clear() {
		template.clear();
	}
	public <T extends Element> Cell<T> add(T element) {
		return template.add(element);
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
