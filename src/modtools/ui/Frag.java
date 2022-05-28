package modtools.ui;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import mindustry.ui.Styles;
import modtools.ui.components.MoveListener;
import modtools.ui.content.Content;

import java.util.Objects;

import static modtools.IntVars.modName;

public class Frag extends Table {

	public void load() {
		name = modName + "-frag";
		new MoveListener(image().color(Color.sky).margin(0).pad(0)
				.padBottom(-4).fillX().height(40).get(), this);
		row();
		Contents.load();
		table(Styles.none, (t) -> {
			Content.all.forEach(cont -> {
				if (cont == null || !cont.loadable()) return;
				String localizedName = cont.localizedName();
				TextButtonStyle style = Styles.flatt;
				Objects.requireNonNull(cont);
				cont.btn = t.button(localizedName, style, cont::build).size(120, 40).get();
				cont.load();
				t.row();
			});
		}).row();
		left().bottom();
		Core.scene.add(this);
		Log.info(this);
	}
}
