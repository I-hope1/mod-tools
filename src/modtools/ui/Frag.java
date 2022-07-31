package modtools.ui;

import arc.Core;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import mindustry.ui.Styles;
import modtools.ui.components.MoveListener;
import modtools.ui.content.Content;

import static modtools.IntVars.modName;
import static modtools.IntVars.topGroup;
import static modtools.utils.MySettings.settings;

public class Frag extends Table {
	public boolean keepFrag = settings.getBool("ShowMainMenuBackground", "true"), hideCont = false;
	public int baseHeight = 0;
	public Table contTable;
	public Cell<Table> cell;

	public void load() {
//		MyPacket.register();
		name = modName + "-frag";
		Image top = image().color(Color.sky).margin(0).pad(0)
				.padBottom(-4).fillX().minWidth(40).height(40).get();
		row();
		new MoveListener(top, this);
		// 添加双击变小
		IntUI.doubleClick(top, () -> {}, () -> {
			hideCont = !hideCont;
			cell.setElement(hideCont ? null : contTable);
		});
		if (Content.all.isEmpty()) Contents.load();
		cell = table().get().table(table -> {
			contTable = table;
			Content.all.forEach(cont -> {
				if (cont == null || !cont.loadable()) return;
				String localizedName = cont.localizedName();
				var style = Styles.flatt;
//				var style = Styles.cleart;
//				Objects.requireNonNull(cont);
				cont.btn = table.button(localizedName, style, cont::build).size(120, 40).get();
				baseHeight += 40;
				cont.load();
				table.row();
			});
		});
		left().bottom();
		topGroup.addChild(this);
		Log.info(this);
		/*x = Core.graphics.getWidth() / -2f;
		y = Core.graphics.getHeight() / -2f;*/

		update(() -> {
			setPosition(Mathf.clamp(x, 0f, (float) Core.graphics.getWidth() - getPrefWidth()),
					Mathf.clamp(y, 0f, (float) Core.graphics.getHeight() - getPrefHeight()));
			/*if (Vars.state.isGame() && Vars.net.server()) {
				var p = new MyPacket();
				p.aBoolean = false;
				Vars.net.send(p, true);
			}*/
			if (!keepFrag || parent == null || parent.getChildren().peek() == this) return;
			setZIndex(Integer.MAX_VALUE);
		});
	}
}
