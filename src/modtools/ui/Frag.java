package modtools.ui;

import arc.Core;
import arc.graphics.Color;
import arc.math.*;
import arc.scene.*;
import arc.scene.actions.Actions;
import arc.scene.event.Touchable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.Seq;
import arc.util.*;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.ui.components.limit.LimitTable;
import modtools.ui.components.linstener.MoveListener;
import modtools.ui.content.Content;
import modtools.utils.ui.LerpFun;

import static modtools.IntVars.modName;
import static modtools.ui.IntUI.topGroup;

public class Frag extends Table {
	public final Color defaultColor = Color.sky;

	public boolean hideCont = false;
	public ScrollPane container;
	Cell<?> cell;
	Group   circle;
	Image   top;

	Seq<Content> enabledContents = new Seq<>();

	public void load() {
		touchable = Touchable.enabled;
		//		MyPacket.register();
		name = modName + "-frag";
		top = image().color(defaultColor).margin(0).pad(0)
		 .padBottom(-4).fillX().minWidth(40).height(40).get();
		row();

		if (Content.all.isEmpty()) Contents.load();

		container = new ScrollPane(new LimitTable(table -> {
			Content.all.forEach(content -> {
				if (content == null || !content.loadable()) return;
				enabledContents.add(content);
				String localizedName = content.localizedName();
				var    style         = IntStyles.flatt;
				// var style = Styles.cleart;
				// Objects.requireNonNull(cont);
				content.btn = table.button(localizedName,
					content.icon,
					style, content.icon == Styles.none ? 0 : 20,
					content::build)
				 .marginLeft(6f)
				 .size(120, 40).get();
				content.load();
				table.row();
			});
		}), IntStyles.noBarPane);
		// lastIndex = getCells().indexOf(cell);
		container.update(() -> container.setOverscroll(false, false));
		cell = add(container);
		Runnable addCont = () -> {
			cell.setElement(container);
			cell.size(120, 40 * 5 + 1);
		}, removeCont = () -> {
			cell.clearElement();
			cell.size(0);
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
		IntUI.doubleClick(top, () -> {
			if (!hideCont) return;
			if (circle == null) addChild(circle = new Group() {{
				update(() -> {
					width = top.getWidth();
					height = top.getHeight();
					setPosition(0, 0, Align.center);
				});
			}});
			if (circle.getChildren().any()) circleRemove();
			else circleBuild();
		}, () -> {
			hideCont = !hideCont;
			if (hideCont) {
				removeCont.run();
			} else {
				addCont.run();
			}
			invalidate();
			circleRemove();
			Core.app.post(() -> listener.display(x, y));
		});
		IntVars.addResizeListener(() -> listener.display(x, y));
	}
	private void circleBuild() {
		float angle = 90;
		for (Content content : enabledContents) {
			ImageButton image = new ImageButton(content.icon, IntStyles.flati);
			image.setTransform(true);
			float finalAngle = angle;
			angle -= 30;
			new LerpFun(Interp.smooth).onUI().registerDispose(1 / 24f, f -> {
				float rotation1 = Mathf.lerp(0, finalAngle, f);
				// image.setRotation(rotation1);
				float radius = Mathf.lerp(0, 72, f);
				image.setPosition(
				 top.getWidth() / 2f + radius * Mathf.cosDeg(rotation1),
				 top.getHeight() / 2f + radius * Mathf.sinDeg(rotation1));/* Align.center); */
			});
			image.update(() -> {
				image.setSize(42 * Scl.scl());
				image.setOrigin(Align.center);
			});
			image.clicked(content::build);
			circle.addChild(image);
		}
		top.actions(Actions.color(Color.pink, 0.1f));
	}
	private void circleRemove() {
		if (circle == null) return;
		top.actions(Actions.color(defaultColor, 0.1f));
		for (Element child : circle.getChildren()) {
			if (child.hasActions()) continue;
			child.actions(Actions.moveTo(
			 top.getWidth() / 2f,
			 top.getHeight() / 2f, 0.1f,
			 Interp.smooth), Actions.remove());
		}
	}
}
