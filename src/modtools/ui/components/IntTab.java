package modtools.ui.components;

import arc.graphics.Color;
import arc.math.Interp;
import arc.scene.Element;
import arc.scene.actions.Actions;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.Log;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import modtools.ui.HopeStyles;
import modtools.ui.components.input.MyLabel;
import modtools.ui.components.limit.PrefTable;

import java.util.Arrays;

public class IntTab {
	private final float      duration = 0.07f;
	public        Table      main;
	public        PrefTable  title;
	public        ScrollPane pane;

	public String[] names;
	public Color[]  colors;
	public Table[]  tables;
	/** 这些会乘以{@link Scl#scl} */
	public float    totalWidth, eachWidth;
	public int     cols;
	public boolean column/*  = false */;

	public void setTotalWidth(float amount) {
		totalWidth = amount;
	}

	protected void init() {
		if (main != null) return;
		labels = new ObjectMap<>(names.length);
		title = new PrefTable();
		title.defaults().growX().height(42);
		pane = new ScrollPane(null, Styles.smallPane);
		main = new Table(t -> {
			t.pane(title).top().fill()
			 .self(c -> c.update(__ -> c.minHeight(title.getMinHeight() / Scl.scl())));
			if (!column) t.row();
			t.add(pane).self(c -> c.width(totalWidth)).grow();
		}) {
			public float getMinWidth() {
				return prefW != -1 ? prefW : super.getMinWidth();
			}
			public float getMinHeight() {
				return prefH != -1 ? prefH : super.getMinHeight();
			}
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

	int selected = -1;
	public int getSelected() {
		return selected;
	}
	boolean transitional = false;
	Element first        = null;
	float   prefW        = -1, prefH = -1;

	public void setPrefSize(float w, float h) {
		prefW = w;
		prefH = h;
		main.pack();
	}

	public ObjectMap<String, Label> labels;
	boolean hideTitle;
	public Table build() {
		if (totalWidth == -1) title.defaults().growX();

		for (int i = 0; i < tables.length; ) {
			Table t = tables[i];
			int   j = i;
			title.button(b -> {
				 if (first == null) first = b;
				 labels.put(names[j], b.add(new MyLabel(() -> hideTitle ? "" + Character.toUpperCase(names[j].charAt(0)) : names[j]
				 )).color(colors[j]).padLeft(4f).padRight(4f).growY().get());
				 b.row();
				 Image image = b.image().growX().get();
				 b.update(() -> {
					 image.setColor(selected == j ? colors[j] : Color.gray);
				 });
			 }, HopeStyles.clearb, () -> {
				 if (selected != j && !transitional) {
					 if (selected != -1) {
						 Table last = tables[selected];
						 last.actions(Actions.fadeOut(duration, Interp.fade), Actions.remove());
						 transitional = true;
						 title.update(() -> {
							 if (!last.hasActions()) {
								 transitional = false;
								 title.update(null);
								 selected = j;
								 pane.setWidget(t);
								 t.actions(Actions.alpha(0), Actions.fadeIn(duration, Interp.fade));
							 }
						 });
					 } else {
						 pane.setWidget(t);
						 selected = j;
					 }
				 }
			 })
			 .self(c -> {
				 if (totalWidth != -1)
					 c.width(eachWidth != 0 ? eachWidth : Math.max(totalWidth / (float) cols, c.get().getPrefWidth() / Scl.scl()));
			 });

			if (++i % cols == 0) title.row();
		}
		title.row();
		first.fireClick();
		if (column) {
			title.add().height(/** {@link Cell#unset} */Float.NEGATIVE_INFINITY).growY().row();
			title.left().defaults().left();
			title.fill().left().bottom().button(Icon.menuSmall, Styles.flati, () -> {
				hideTitle = !hideTitle;
				title.invalidateHierarchy();
			}).size(24f);
		}

		/* ScrollPane pane1 = (ScrollPane) title.parent;
		pane1.setScrollingDisabled(
		 title.getPrefWidth() <= main.getPrefWidth(),
		 title.getPrefHeight() <= main.getPrefHeight()); */
		return main;
	}
}
