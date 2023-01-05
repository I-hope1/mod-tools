
package modtools.ui.components;

import arc.graphics.Color;
import arc.math.Interp;
import arc.scene.Element;
import arc.scene.actions.Actions;
import arc.scene.ui.Image;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Log;
import modtools.ui.IntStyles;

public class IntTab {
	public Table main, title;
	public ScrollPane pane;
	public Seq<String> names;
	public Seq<Color> colors;
	public Seq<Table> tables;
	public float totalWidth;
	public int cols;

	protected void init() {
		if (main != null) return;
		title = new Table();
		pane = new ScrollPane(null);
		main = new Table(t -> {
			t.add(title).width(totalWidth).top().row();
			t.add(pane).grow();
		});
	}

	/**
	 * @param totalWidth 总宽度
	 * @param names      名称
	 * @param colors     颜色
	 * @param tables     Tables
	 * @param cols       一行的个数
	 * @throws IllegalArgumentException size must be the same.
	 */
	public static IntTab set(float totalWidth, Seq<String> names, Seq<Color> colors, Seq<Table> tables, int cols) {
		return new IntTab(totalWidth, names, colors, tables, cols);
	}

	public static IntTab set(float totalWidth, Seq<String> names, Seq<Color> colors, Seq<Table> tables) {
		return new IntTab(totalWidth, names, colors, tables, Integer.MAX_VALUE);
	}

	public IntTab(float totalWidth, Seq<String> names, Seq<Color> colors, Seq<Table> tables, int cols) {
		if (names.size != colors.size || names.size != tables.size) {
			Log.info("name: @, color: @, table: @", names.size, colors.size, tables.size);
			throw new IllegalArgumentException("size must be the same.");
		}

		this.totalWidth = totalWidth;
		this.names = names;
		this.colors = colors;
		this.tables = tables;
		this.cols = cols;
		init();
	}

	public Table build() {
		byte[] selected = {-1};
		boolean[] transitional = {false};
		Element[] first = {null};

		for (byte i = 0; i < tables.size; i++) {
			Table t = tables.get(i);
			byte j = i;
			title.button(b -> {
				if (first[0] == null) first[0] = b;
				b.add(names.get(j), colors.get(j)).padRight(15.0f).growY().row();
				Image image = b.image().fillX().growX().get();
				b.update(() -> {
					image.setColor(selected[0] == j ? colors.get(j) : Color.gray);
				});
			}, IntStyles.clearb, () -> {
				if (selected[0] != j && !transitional[0]) {
					if (selected[0] != -1) {
						Table last = tables.get(selected[0]);
						last.actions(Actions.fadeOut(0.2f, Interp.fade), Actions.remove());
						transitional[0] = true;
						title.update(() -> {
							if (!last.hasActions()) {
								transitional[0] = false;
								title.update(null);
								selected[0] = j;
								pane.setWidget(t);
								t.actions(Actions.alpha(0), Actions.fadeIn(0.3f, Interp.fade));
							}
						});
					} else {
						pane.setWidget(t);
						selected[0] = j;
					}

				}
			}).height(42).growX();
			if ((j + 1) % cols == 0) title.row();
		}
		title.row();
		first[0].fireClick();
		return main;
	}
}
