
package modtools.ui.components;

import arc.graphics.Color;
import arc.math.Interp;
import arc.scene.Element;
import arc.scene.actions.Actions;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.Log;
import modtools.ui.IntStyles;

import java.util.Arrays;

public class IntTab {
	public Table main, title;
	public ScrollPane pane;
	public String[]   names;
	public Color[]    colors;
	public Table[]    tables;
	public float      totalWidth;
	public int        cols;
	public boolean    column;

	public void setTotalWidth(float amount) {
		totalWidth = amount;
	}

	protected void init() {
		if (main != null) return;
		title = new Table();
		title.defaults().growX().height(42);
		pane = new ScrollPane(null);
		main = new Table(t -> {
			t.add(title).self(c -> {
				if (totalWidth == -1) {
					c.growX();
				} else {
					c.width(totalWidth);
				}
			}).top();
			if (!column) t.row();
			t.add(pane).minWidth(totalWidth).grow();
		}) {
			public float getPrefWidth() {
				return prefW != -1 ? prefW : super.getPrefWidth();
			}

			public float getPrefHeight() {
				return prefH != -1 ? prefH : super.getPrefHeight();
			}
		};
	}

	public static Color[] fillColor(int size, Color color) {
		Color[] colors = new Color[size];
		Arrays.fill(colors, color);
		return colors;
	}

	/**
	 * 自适配颜色
	 */
	public IntTab(float totalWidth, String[] names, Color color, Table[] tables) {
		this(totalWidth, names,
		 fillColor(names.length, color),
		 tables);
	}

	public IntTab(float totalWidth, String[] names, Color color, Table[] tables, int cols, boolean column) {
		this(totalWidth, names,
		 fillColor(names.length, color),
		 tables, cols, column);
	}


	public IntTab(float totalWidth, String[] names, Color[] colors, Table[] tables) {
		this(totalWidth, names, colors, tables, Integer.MAX_VALUE, false);
	}

	/**
	 * @param totalWidth 总宽度（如果为纵向，totalWidth是标题宽度）
	 * @param names      名称
	 * @param colors     颜色
	 * @param tables     Tables
	 * @param cols       一行的个数
	 * @param column     是否为纵向排列
	 *
	 * @throws IllegalArgumentException size must be the same.
	 */
	public IntTab(float totalWidth, Seq<String> names, Seq<Color> colors, Seq<Table> tables, int cols, boolean column) {
		this(totalWidth, names.toArray(String.class),
		 colors.toArray(Table.class),
		 tables.toArray(Table.class), cols, column);
	}
	public IntTab(float totalWidth, String[] names, Color[] colors, Table[] tables, int cols, boolean column) {
		if (names.length != colors.length || names.length != tables.length) {
			Log.err("name: @, color: @, table: @", names.length, colors.length, tables.length);
			throw new IllegalArgumentException("size must be the same.");
		}
		if (names.length == 0) throw new RuntimeException("size can't be 0.");

		this.totalWidth = totalWidth;
		this.names = names;
		this.colors = colors;
		this.tables = tables;
		this.cols = cols;
		this.column = column;
		init();
	}

	byte selected = -1;
	public byte getSelected() {
		return selected;
	}
	boolean transitional = false;
	Element first        = null;
	float   prefW        = -1, prefH = -1;

	public void setPrefSize(float w, float h) {
		prefW = w;
		prefH = h;
	}

	public ObjectMap<String, Label> labels = new ObjectMap<>();
	public Table build() {
		for (byte i = 0; i < tables.length; i++) {
			Table t = tables[i];
			byte  j = i;
			Cell cell = title.button(b -> {
				if (first == null) first = b;
				labels.put(names[j], b.add(names[j], colors[j]).growY().get());
				b.row();
				Image image = b.image().growX().get();
				b.update(() -> {
					image.setColor(selected == j ? colors[j] : Color.gray);
				});
			}, IntStyles.clearb, () -> {
				if (selected != j && !transitional) {
					if (selected != -1) {
						Table last = tables[selected];
						last.actions(Actions.fadeOut(0.03f, Interp.fade), Actions.remove());
						transitional = true;
						title.update(() -> {
							if (!last.hasActions()) {
								transitional = false;
								title.update(null);
								selected = j;
								pane.setWidget(t);
								t.actions(Actions.alpha(0), Actions.fadeIn(0.3f, Interp.fade));
							}
						});
					} else {
						pane.setWidget(t);
						selected = j;
					}

				}
			});
			cell.width(totalWidth / (float) cols);
			/* if (totalWidth == -1) {
				cell.update(__ -> {
					cell.width(main.getWidth() * (cols / (float) tables.size));
				});
			} */
			if ((j + 1) % cols == 0) title.row();
		}
		title.row();
		first.fireClick();
		return main;
	}
}
