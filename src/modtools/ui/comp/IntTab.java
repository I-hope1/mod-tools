package modtools.ui.comp;

import arc.func.Prov;
import arc.graphics.Color;
import arc.math.*;
import arc.scene.*;
import arc.scene.actions.Actions;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.Log;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import modtools.ui.HopeStyles;
import modtools.ui.comp.input.MyLabel;
import modtools.ui.comp.limit.PrefTable;
import modtools.ui.effect.HopeFx;
import modtools.utils.ui.*;

import java.util.Arrays;

public class IntTab {
	private final float      duration = 0.1f;
	public        Table      main;
	public        PrefTable  title;
	public        ScrollPane pane;

	private Drawable[] icons;
	public  String[]   names;
	public  Color[]    colors;
	public  Table[]    tables;
	/** 这些会乘以{@link Scl#scl} */
	public  float      titleWidth, eachWidth;
	public int     cols;
	public boolean column/* = false */;

	public void setTitleWidth(float amount) {
		titleWidth = amount;
	}

	protected void init() {
		if (main != null) return;
		labels = new ObjectMap<>(names.length);
		title = new PrefTable();
		title.defaults().growX().height(42);
		pane = new ScrollPane(null, Styles.smallPane);
		main = new Table(t -> {
			Cell<ScrollPane> cell = t.pane(title).top();
			cell.update(p -> {
				p.setScrollingDisabled(column, !column);
				if (column) {
					cell.width(p.getPrefWidth());
				} else {
					cell.height(p.getPrefHeight());
				}
			});
			if (column) {
				cell.growY();
			} else {
				cell.growX().row();
			}
			t.add(pane).pad(4).self(c -> c.update(_ -> c.width(titleWidth == -1 ? CellTools.unset : titleWidth))).grow();
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
	public IntTab(float titleWidth, String[] names, Color color, Table[] tables) {
		this(titleWidth, names,
		 fillColor(names.length, color),
		 tables);
	}

	public IntTab(float titleWidth, String[] names, Color color, Table[] tables, int cols, boolean column) {
		this(titleWidth, names,
		 fillColor(names.length, color),
		 tables, cols, column);
	}


	public IntTab(float titleWidth, String[] names, Color[] colors, Table[] tables) {
		this(titleWidth, names, colors, tables, Integer.MAX_VALUE, false);
	}

	/**
	 * @param titleWidth 总宽度（如果为纵向，totalWidth是标题宽度）
	 * @param names      名称
	 * @param colors     颜色
	 * @param tables     Tables
	 * @param cols       一行的个数
	 * @param column     是否为纵向排列
	 * @throws IllegalArgumentException size must be the same.
	 */
	public IntTab(float titleWidth, Seq<String> names, Seq<Color> colors, Seq<Table> tables, int cols, boolean column) {
		this(titleWidth, names.toArray(String.class),
		 colors.toArray(Table.class),
		 tables.toArray(Table.class), cols, column);
	}
	public IntTab(float titleWidth, String[] names, Color[] colors, Table[] tables, int cols, boolean column) {
		if (names.length != colors.length || names.length != tables.length) {
			Log.err("name: @, color: @, table: @", names.length, colors.length, tables.length);
			throw new IllegalArgumentException("size must be the same.");
		}
		if (names.length == 0) throw new RuntimeException("size can't be 0.");

		this.titleWidth = titleWidth;
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
		prefW = w * Scl.scl();
		prefH = h * Scl.scl();
		main.pack();
	}
	public void setIcons(Drawable... icons) {
		this.icons = icons;
	}

	public ObjectMap<String, Label> labels;
	boolean hideTitle;
	public Table build() {
		if (titleWidth == -1) title.defaults().growX();
		// pane.setScrollingDisabled(!column, column);

		for (int i = 0; i < tables.length; ) {
			Table t = tables[i];
			int   j = i;
			title.button(b -> {
				if (first == null) first = b;
				labels.put(names[j], b.add(new TitleLabel(
					() -> hideTitle ? "" : names[j],
					icons == null ? null : icons[j]
				 )).color(colors[j]).padLeft(4f)
				 .padRight(4f).minWidth(28).growY().get());
				b.row();
				Cell<Image> image = b.image().growX();
				b.update(() -> {
					image.color(selected == j ? colors[j] : Color.gray);
				});
			}, HopeStyles.clearb, () -> {
				if (selected != j && !transitional) {
					if (selected != -1) {
						Table last = tables[selected];
						last.actions(getActionOut(j), Actions.remove());
						transitional = true;
						// JSFunc.watch().watch("Last", () -> last);
						title.update(() -> {
							if (!last.hasActions()) {
								transitional = false;
								title.update(null);
								t.addAction(getActionIn(j));
								pane.setWidget(t);
								selected = j;
							}
						});
					} else {
						pane.setWidget(t);
						selected = j;
					}
				}
			}).self(c -> {
				if (titleWidth != -1)
					c.width(eachWidth != 0 ? eachWidth : Math.max(titleWidth / (float) cols, c.get().getPrefWidth() / Scl.scl()));
			});

			if (++i % cols == 0) title.row();
		}
		title.row();
		first.fireClick();
		if (column) {
			title.add().height(CellTools.unset).growY().row();
			title.left().defaults().left();
			title.fill().left().bottom().button(Icon.menuSmall, HopeStyles.flati, () -> {
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
	// 创建进入动画
	private Action getActionIn(int toIndex) {
		float duration = 0.16f; // 动画时长，可以根据需要调整

		float targetX = column ? 0 : main.getWidth() * 0.7f;
		float targetY = column ? main.getHeight() * -0.7f : 0;

		// 如果是竖向排列
		int sign = Mathf.sign(toIndex > getSelected());
		if (column) {
			targetY *= sign;
		} else {
			targetX *= sign;
		}

		return Actions.parallel(
		 Actions.sequence(HopeFx.translateTo(targetX, targetY, 0),
			HopeFx.translateTo(0, 0, duration, Interp.fastSlow)),
		 Actions.fadeIn(duration, Interp.fastSlow)
		);
	}

	// 创建退出动画
	private Action getActionOut(int toIndex) {
		float duration = 0.16f; // 动画时长，可以根据需要调整

		float targetX = column ? 0 : main.getWidth() * 0.7f;
		float targetY = column ? main.getHeight() * -0.7f : 0;

		// 如果是竖向排列
		if (column) {
			targetY *= toIndex > getSelected() ? -1 : 1;
		} else {
			targetX *= toIndex > getSelected() ? -1 : 1;
		}

		return Actions.parallel(
		 HopeFx.translateTo(targetX, targetY, duration, Interp.fastSlow),
		 Actions.alpha(0.2f, duration, Interp.slowFast)
		);
	}
	public static class TitleLabel extends MyLabel {
		Drawable icon;
		public TitleLabel(Prov<CharSequence> sup, Drawable icon) {
			super(sup);
			this.icon = icon;
		}
		public void act(float delta) {
			super.act(delta);
			style.background = text.length() == 0 ? icon : null;
		}
	}
}
