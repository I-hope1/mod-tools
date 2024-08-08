
package modtools.ui.comp.utils;

import arc.func.*;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.Tex;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.ui.*;
import modtools.utils.search.FilterTable.PatternBoolf;
import modtools.utils.search.*;

import java.util.regex.Pattern;

public class MyItemSelection {
	private static TextField search;

	public static <T extends UnlockableContent> void buildTable(Table table, Seq<T> items, Prov<T> holder,
	                                                            Cons<T> consumer) {
		buildTable(table, items, holder, consumer, 4);
	}

	public static <T extends UnlockableContent> void buildTable(Table table, Seq<T> items, Prov<T> holder,
	                                                            Cons<T> consumer, int cols) {
		IntVars.async(() -> buildTable0(table, items, holder, consumer, cols), IntVars.EMPTY_RUN);
	}
	private static <T extends UnlockableContent> void buildTable0(
	 Table table, Seq<T> items, Prov<T> holder,
	 Cons<T> consumer, int cols) {
		buildTable0(table, items, holder, consumer, cols, IntUI::icon);
	}

	public static <T> void buildTable0(
	 Table table, Seq<T> items, Prov<T> holder,
	 Cons<T> consumer, int cols, Func<T, Drawable> drawableFunc) {
		Pattern[] pattern = {null};
		new Search((_, p) -> pattern[0] = p).build(table, null);

		ButtonGroup<ImageButton> group = new ButtonGroup<>();
		group.setMinCheckCount(0);
		TemplateTable<T> cont = new TemplateTable<>(null, new PatternBoolf<>(() -> pattern[0]));
		cont.left().top().defaults().left().top().size(40);
		int i = 0;

		for (T item : items) {
			if (item == null) continue;
			try {
				cont.bind(item);
				ImageButton button = cont.button(Tex.whiteui, /*Styles.clearNoneTogglei*/HopeStyles.clearNoneTogglei, 24, IntVars.EMPTY_RUN).group(group).get();
				button.changed(() -> consumer.get(button.isChecked() ? item : null));
				button.getStyle().imageUp = drawableFunc.get(item);
				if (item == holder.get()) button.setChecked(true);
				cont.unbind();
			} catch (Exception ignored) { }
			if (++i % cols == 0) {
				cont.row();
			}
		}



		ScrollPane pane = new ScrollPane(cont, Styles.smallPane);
		pane.setScrollingDisabled(true, false);
		pane.setOverscroll(false, false);
		table.add(pane).maxHeight(40 * 8).grow();
	}

	/** 支持多元素选择 */
	public static <T> ButtonGroup<ImageButton> buildTable0(
	 Table table, Seq<T> items, int cols, Func<T, Drawable> drawableFunc) {
		var group = new ButtonGroup<ImageButton>();
		group.setMinCheckCount(1);
		Table cont = new Table();
		cont.defaults().size(40);
		int i = 0;

		for (T item : items) {
			if (item == null) continue;
			try {
				ImageButton button = cont.button(Tex.whiteui, /*Styles.clearNoneTogglei*/HopeStyles.clearNoneTogglei,
					24, IntVars.EMPTY_RUN)
				 .group(group).get();
				button.name = item.toString();
				button.userObject = item;
				button.getStyle().imageUp = drawableFunc.get(item);
				// button.clicked(() -> button.setChecked(!button.isChecked()));
			} catch (Exception ignored) { }
			if (++i % cols == 0) {
				cont.row();
			}
		}

		ScrollPane pane = new ScrollPane(cont, Styles.smallPane);
		pane.setScrollingDisabled(true, false);
		pane.setOverscroll(false, false);
		table.add(pane).maxHeight(40 * 5).grow();

		return group;
	}

}
