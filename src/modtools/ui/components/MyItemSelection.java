package modtools.ui.components;

import arc.func.Cons;
import arc.func.Prov;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.ButtonGroup;
import arc.scene.ui.ImageButton;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.Tex;
import mindustry.ui.Styles;

public class MyItemSelection {

	public static <T extends UnlockableContent> void buildTable(Table table, Seq<T> items, Prov<T> holder,
			Cons<T> consumer) {
		buildTable(table, items, holder, consumer, 4);
	}

	public static <T extends UnlockableContent> void buildTable(Table table, Seq<T> items, Prov<T> holder,
			Cons<T> consumer, int cols) {

		ButtonGroup<ImageButton> group = new ButtonGroup<>();
		group.setMinCheckCount(0);
		Table cont = new Table();
		cont.defaults().size(40);

		int i = 0;

		for (T item : items) {
			ImageButton button = cont.button(Tex.whiteui, Styles.clearNoneTogglei, 24, () -> {
			}).group(group).get();
			button.changed(() -> consumer.get(button.isChecked() ? item : null));
			button.getStyle().imageUp = new TextureRegionDrawable(item.uiIcon);
			button.update(() -> button.setChecked(holder.get() == item));

			if (++i % cols == 0) {
				cont.row();
			}
		}

		ScrollPane pane = new ScrollPane(cont, Styles.smallPane);
		pane.setScrollingDisabled(true, false);

		pane.setOverscroll(false, false);
		table.add(pane).maxHeight(200);
	}
}
