package modtools.world;

import arc.func.Boolf;
import arc.func.Cons;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.ButtonGroup;
import arc.scene.ui.ImageButton;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.Tex;
import mindustry.ui.Styles;

public class ItemSelection {
	public static int COLS = 5;
	public static <T extends UnlockableContent> void buildSelection(Table table, Seq<T> items, Cons<T> onSelect, Boolf<T>boolf) {
		// Seq<Item> items = Vars.content.items();
		ButtonGroup<ImageButton> group = new ButtonGroup<>();
		group.setMinCheckCount(0);
		group.setMaxCheckCount(-1);
		Table cont = new Table();
		cont.defaults().size(40);
		int i = 0;
		for (var item : items) {
			if (item.unlockedNow()) {
				ImageButton button = cont.button(Tex.whiteui, Styles.clearTogglei, 24, () -> {
				}).group(group).get();
				button.changed(() -> onSelect.get(item));
				button.getStyle().imageUp = new TextureRegionDrawable(item.uiIcon);
				button.update(() -> {
					button.setChecked(boolf.get(item));
				});
				if (++i % COLS == 0) {
					cont.row();
				}
			}
		}

		ScrollPane pane = new ScrollPane(cont, Styles.smallPane);
		pane.setScrollingDisabled(true, false);

		pane.setOverscroll(false, false);
		table.add(pane).maxHeight(Scl.scl(200));
	}
}
