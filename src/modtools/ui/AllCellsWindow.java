package modtools.ui;

import arc.func.Cons;
import arc.scene.style.Drawable;
import arc.scene.ui.layout.*;
import mindustry.gen.Tex;
import modtools.content.SettingsUI.SettingsBuilder;
import modtools.content.ui.ReviewElement;
import modtools.content.ui.ReviewElement.CellView;
import modtools.ui.comp.*;
import modtools.ui.comp.Window.IDisposable;
import modtools.ui.comp.utils.PlainValueLabel;
import modtools.utils.search.FilterTable;
import modtools.utils.ui.CellTools;

public class AllCellsWindow extends Window implements IDisposable {
	public boolean ignoreEmptyCell = true;
	public Table inspectTable;
	public AllCellsWindow(Table inspectTable) {
		super("All Cells");
		this.inspectTable = inspectTable;
		init();
	}
	/**
	 * 为单个 Cell 创建一个可视化的 Table。
	 * @param cell 需要可视化的 Cell
	 * @return 代表该 Cell 的 Table
	 */
	private Table createCellItemView(Cell<?> cell) {
		return new CellItem(Tex.button, t -> {
			// 创建一个标签，用于显示 Cell 的基本信息
			var label = new PlainValueLabel<>(Cell.class, () -> cell);

			// 添加聚焦功能：点击这个UI项时，可以高亮显示原始表格中的对应元素
			ReviewElement.addFocusSource(t, () -> this, cell::get);

			t.add(label).grow();
		});
	}
	void init() {
		FilterTable<Cell<?>> container = new FilterTable<>();
		container.addConditionUpdateListener(c -> !ignoreEmptyCell || c.hasElement());
		SettingsBuilder.build(cont);
		SettingsBuilder.check("Filter out empty cell", b -> ignoreEmptyCell = b, () -> ignoreEmptyCell);
		SettingsBuilder.clearBuild();
		cont.pane(container).grow();
		container.left().defaults().left();

		for (var cell : inspectTable.getCells()) {
			container.bind(cell);
			container.add(createCellItemView(cell))
			 .grow()
			 .colspan(CellTools.colspan(cell));

			// 如果是换行符，则添加一条下划线
			if (cell.isEndRow()) {
				Underline.of(container.row(), 20);
			}
			container.unbind();
		}
		update(this::display);
	}

	public static class CellItem extends Table implements CellView {
		public CellItem(Drawable background, Cons<Table> cons) {
			super(background, cons);
		}
	}
}
