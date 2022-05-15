package modmake.ui.components;

import arc.func.Cons2;
import arc.func.Prov;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import mindustry.ui.Styles;

public class TabSelection {
	public static Table build(Seq<String> list, Prov<String> holder, Cons2<String, Integer> cons,
			int width, int height) {
		return build(list, holder, cons, Styles.cleart, width, height, Integer.MAX_VALUE);
	}

	public static Table build(Seq<String> list, Prov<String> holder, Cons2<String, Integer> cons,
			int width, int height, int cols) {
		return build(list, holder, cons, Styles.cleart, width, height, cols);
	}

	public static Table build(Seq<String> list, Prov<String> holder, Cons2<String, Integer> cons,
			TextButton.TextButtonStyle style, int width, int height, int cols) {
		return build(list, holder, cons, style, width, height, true, cols);

	}

	public static Table build(Seq<String> list, Prov<String> holder, Cons2<String, Integer> cons,
			TextButton.TextButtonStyle style, int width, int height, boolean checked, int cols) {
		Table p = new Table();
		p.clearChildren();

		int c = 0;
		int[] selected = { 0 };
		Seq<TextButton> seq = new Seq<>();
		for (String item : list) {
			final int _j = c++;
			seq.add(p.button(item, style, () -> {
				seq.get(selected[0]).setChecked(false);
				selected[0] = _j;
				cons.get(item, _j);
			}).size(width, height).get());
			if (c % cols == 0)
				p.row();
		}
		p.update(() -> {
			seq.get(selected[0]).setChecked(true);
		});
		return p;
	}
}
