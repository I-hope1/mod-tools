package modtools.ui.components;

import arc.graphics.Color;
import arc.math.Interp;
import arc.scene.actions.Actions;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import modtools.ui.IntStyles;

/*
 * Tab栏切换
 */
public class IntTab {

	public Table main, title, cont;
	public Seq<String> names;
	public Seq<Color> colors;
	public Seq<Table> tables;
	public float totalWidth;

	protected void init() {
		if (main != null)
			return;
		title = new Table();
		cont = new Table();
		main = new Table(t -> {
			t.add(title).row();
			t.pane(cont).width(totalWidth).fillY();
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
		if (!(names.size == colors.size && names.size == tables.size))
			throw new IllegalArgumentException("size must be the same.");
		this.totalWidth = totalWidth;
		this.names = names;
		this.colors = colors;
		this.tables = tables;
		init();
	}

	public Table build() {
		byte[] selected = { -1 };
		boolean[] transitional = { false };
		for (byte i = 0; i < tables.size; i++) {
			final byte j = i;
			Table t = tables.get(j);
			title.button(b -> {
				b.add(names.get(j), colors.get(j)).padRight(10f + 5f).growY().row();

				Image image = b.image().fillX().growX().get();
				b.update(() -> image.setColor(selected[0] == j ? colors.get(j) : Color.gray));
			}, IntStyles.clearb, () -> {
				if (selected[0] == j || transitional[0])
					return;
				if (selected[0] != -1) {
					tables.get(selected[0]).actions(Actions.fadeOut(0.2f, Interp.fade), Actions.remove());
					transitional[0] = true;
					title.update(() -> {
						if (t.hasActions())
							return;
						transitional[0] = false;
						title.update(null);
						selected[0] = j;
						cont.add(t);
						t.actions(Actions.alpha(0), Actions.fadeIn(0.3f, Interp.fade));
					});
				} else {
					cont.add(t);
					selected[0] = j;
				}
			}).size(totalWidth / (float) names.size, 42);
		}
		title.getChildren().get(0).fireClick();

		return main;
	}
}
