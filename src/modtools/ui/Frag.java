package modtools.ui;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import mindustry.ui.Styles;
import modtools.ui.components.MoveListener;
import modtools.ui.content.Content;

import static modtools.IntVars.modName;

public class Frag extends Table {
	public void load() {
		name = modName + "-frag";

		new MoveListener(image().color(Color.sky).margin(0f).pad(0f).padBottom(-4f).fillX().height(40f)
				.get(), this);
		row();
		Contents.load();
		table(Styles.none, t -> Content.all.forEach(cont -> {
			if (cont == null || !cont.loadable())
				return;

			cont.btn = t.button(cont.localizedName(), Styles.flatt, cont::build).size(120f, 40f).get();
			cont.load();
			t.row();
		})).row();
		left().bottom();

		Core.scene.add(this);
		Log.info(this);
	}
}