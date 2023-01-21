
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

import java.util.Arrays;

public class IntTab {
	public Table main, title;
	public ScrollPane pane;
	public Seq<String> names;
	public Seq<Color> colors;
	public Seq<Table> tables;
	public float totalWidth;
	public int cols;
	public boolean column;

	protected void init() {
		if (main != null) return;
		title = new Table();
		title.defaults().growX().height(42);
		pane = new ScrollPane(null);
		main = new Table(t -> {
			t.add(title).width(totalWidth).top();
			if (!column) t.row();
			t.add(pane).grow();
		});
	}

	public static Seq<Color> fillColor(int size, Color color) {
		Color[] colors = new Color[size];
		Arrays.fill(colors, color);
		return new Seq<>(colors);
	}

	/**
	 * 自适配颜色
	 */
	public IntTab(float totalWidth, Seq<String> names, Color color, Seq<Table> tables) {
		this(totalWidth, names, fillColor(names.size, color), tables);
	}

	public IntTab(float totalWidth, Seq<String> names, Color color, Seq<Table> tables, int cols, boolean column) {
		this(totalWidth, names, fillColor(names.size, color), tables, cols, column);
	}


	public IntTab(float totalWidth, Seq<String> names, Seq<Color> colors, Seq<Table> tables) {
		this(totalWidth, names, colors, tables, Integer.MAX_VALUE, false);
	}

	/**
	 * @param totalWidth 总宽度（如果为纵向，totalWidth是标题宽度）
	 * @param names      名称
	 * @param colors     颜色
	 * @param tables     Tables
	 * @param cols       一行的个数
	 * @param column     是否为纵向排列
	 * @throws IllegalArgumentException size must be the same.
	 */
	public IntTab(float totalWidth, Seq<String> names, Seq<Color> colors, Seq<Table> tables, int cols, boolean column) {
		if (names.size != colors.size || names.size != tables.size) {
			Log.info("name: @, color: @, table: @", names.size, colors.size, tables.size);
			throw new IllegalArgumentException("size must be the same.");
		}

		this.totalWidth = totalWidth;
		this.names = names;
		this.colors = colors;
		this.tables = tables;
		this.cols = cols;
		this.column = column;
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
						last.actions(Actions.fadeOut(0.07f, Interp.fade), Actions.remove());
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
			});
			if ((j + 1) % cols == 0) title.row();
		}
		title.row();
		first[0].fireClick();
		return main;
	}
}
