package modtools.ui;

import arc.*;
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
import modtools.ui.components.buttons.CircleImageButton;
import modtools.ui.components.limit.LimitTable;
import modtools.ui.components.linstener.MoveListener;
import modtools.ui.content.Content;
import modtools.utils.ui.LerpFun;
import modtools.utils.ui.search.BindCell;

import static modtools.IntVars.modName;
import static modtools.ui.IntUI.topGroup;

public class Frag extends Table {
	public final Color defaultColor = Color.sky;

	public boolean    hideCont = false;
	public ScrollPane container;
	BindCell cell;
	Group    circle;
	Image    top;

	Seq<Content> enabledContents = new Seq<>();
	public Frag() {
		super(Styles.black8);
	}
	public void load() {
		touchable = Touchable.enabled;
		//		MyPacket.register();
		name = modName + "-frag";
		top = image().color(defaultColor).margin(0).pad(0)
		 .fillX().minWidth(40).height(40).get();
		update(this::pack);
		row();


		container = new ScrollPane(new LimitTable(table -> {
			IntVars.async(() -> {
				if (Content.all.isEmpty()) Contents.load();
				Content.all.forEach(content -> {
					if (content == null || !content.loadable()) return;
					enabledContents.add(content);
					String localizedName = content.localizedName();
					var    style         = HopeStyles.cleart;
					// var style = Styles.cleart;
					// Objects.requireNonNull(cont);
					content.btn = table.button(localizedName,
						content.icon,
						style, content.icon == Styles.none ? 0 : 20,
						content::build)
					 .marginLeft(6f).update(b ->
						b.getChildren().get(1).setColor(b.isDisabled() ? Color.gray : Color.white))
					 .size(120, 40).get();
					Events.fire(content);
					content.load();
					table.row();
				});
			});
		}), HopeStyles.noBarPane);
		// lastIndex = getCells().indexOf(cell);
		container.update(() -> container.setOverscroll(false, false));
		cell = new BindCell(add(container).size(120, 40 * 5 + 1));

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
			cell.toggle(hideCont);
			hideCont = !hideCont;
			invalidate();
			circleRemove();
			Core.app.post(() -> listener.display(x, y));
		});
		IntVars.addResizeListener(() -> listener.display(x, y));
	}
	public       int hoverSize   = 45;
	public final int hoverRadius = 96;
	private void circleBuild() {
		float angle = 90;
		for (Content content : enabledContents) {
			ImageButton image = new CircleImageButton(content.icon, HopeStyles.hope_flati);
			image.setTransform(true);
			float finalAngle = angle;
			angle -= 30;
			new LerpFun(Interp.smooth).onUI().registerDispose(1 / 24f, f -> {
				float rotation1 = Mathf.lerp(0, finalAngle, f);
				// image.setRotation(rotation1);
				float radius = Mathf.lerp(0, hoverRadius, f);
				Core.graphics.requestRendering();
				image.setPosition(
				 top.getWidth() / 2f + radius * Mathf.cosDeg(rotation1),
				 top.getHeight() / 2f + radius * Mathf.sinDeg(rotation1));/* Align.center); */
			});
			image.update(() -> {
				image.setSize(hoverSize * Scl.scl());
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
