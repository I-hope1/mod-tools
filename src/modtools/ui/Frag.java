package modtools.ui;

import arc.Core;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.scene.event.Touchable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.Log;
import modtools.IntVars;
import modtools.ui.components.limit.LimitTable;
import modtools.ui.components.linstener.MoveListener;
import modtools.ui.content.Content;

import static modtools.IntVars.modName;
import static modtools.ui.IntUI.topGroup;

public class Frag extends Table {
	public boolean    hideCont   = false;
	public ScrollPane contPane;
	Cell<?> cell;

	public void load() {
		touchable = Touchable.enabled;
		//		MyPacket.register();
		name = modName + "-frag";
		Image top = image().color(Color.sky).margin(0).pad(0)
		  .padBottom(-4).fillX().minWidth(40).height(40).get();
		row();

		if (Content.all.isEmpty()) Contents.load();

		contPane = new ScrollPane(new LimitTable(table -> {
			Content.all.forEach(cont -> {
				if (cont == null || !cont.loadable()) return;
				String localizedName = cont.localizedName();
				var    style         = IntStyles.flatt;
				// var style = Styles.cleart;
				// Objects.requireNonNull(cont);
				cont.btn = table.button(localizedName, style, cont::build).size(120, 40).get();
				cont.load();
				table.row();
			});
		}), IntStyles.noBarPane);
		// lastIndex = getCells().indexOf(cell);
		contPane.update(() -> contPane.setOverscroll(false, false));
		cell = add(contPane);
		Runnable addCont = () -> {
			cell.setElement(contPane);
			cell.size(120, 40 * 5 + 1);
		}, removeCont = () -> {
			cell.clearElement();
			cell.size(0, 0);
		};

		addCont.run();
		left().bottom();
		topGroup.addChild(this);
		Log.info("Initialize TopGroup.");

		var listener = new MoveListener(top, this) {
			public void display(float x, float y) {
				float mainWidth  = main.getPrefWidth(), mainHeight = main.getPrefHeight();
				float touchWidth = touch.getWidth(), touchHeight = touch.getHeight();
				main.x = Mathf.clamp(x, -touchWidth / 3f, Core.graphics.getWidth() - mainWidth / 2f);
				main.y = Mathf.clamp(y, -mainHeight + touchHeight, Core.graphics.getHeight() - mainHeight);
			}
		};
		// 添加双击变小
		IntUI.doubleClick(top, null, () -> {
			hideCont = !hideCont;
			if (hideCont) {
				removeCont.run();
			} else {
				addCont.run();
			}
			invalidate();
			Core.app.post(() -> listener.display(x, y));
		});
		IntVars.addResizeListener(() -> listener.display(x, y));
	}
}
