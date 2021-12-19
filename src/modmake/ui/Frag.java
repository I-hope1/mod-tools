package modmake.ui;

import arc.scene.ui.layout.Table;
import arc.util.Log;
import arc.util.Tmp;
import mindustry.gen.Tex;
import mindustry.ui.Styles;
import arc.Core;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.math.Interp;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.actions.Actions;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;

public class Frag extends Table {

	public void load() {

		image().color(Color.sky).margin(0f).pad(0f).padBottom(-4f).fillX().height(40f)
				.get().addListener(new InputListener() {
					float bx, by;

					public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
						bx = x;
						by = y;
						return true;
					}

					public void touchDragged(InputEvent event, float x, float y, int pointer) {
						Vec2 v = localToStageCoordinates(Tmp.v1.set(x, y));
						setPosition(
								Mathf.clamp(-bx + v.x, 0f, Core.graphics.getWidth() - getPrefWidth()),
								Mathf.clamp(-by + v.y, 0f, Core.graphics.getHeight() - getPrefHeight()));
					}
				});
		row();
		table(Tex.whiteui, t -> {

			Contents.all.each(cont -> {
				if (cont == null || !cont.loadable())
					return;

				cont.btn = t.button(cont.localizedName(), Styles.cleart, () -> {
					cont.build();
				}).size(120f, 40f).get();
				cont.load();
				t.row();
			});
		}).row();
		left().bottom();

		Core.scene.add(this);
		Log.info(this);
	}
}