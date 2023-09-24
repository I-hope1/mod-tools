package modtools.ui.content.debug;

import arc.func.*;
import arc.graphics.Color;
import arc.scene.*;
import arc.scene.actions.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.ImageButton.ImageButtonStyle;
import arc.struct.Seq;
import arc.util.*;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.events.ExecuteTree;
import modtools.events.ExecuteTree.*;
import modtools.ui.*;
import modtools.ui.HopeIcons;
import modtools.ui.components.Window;
import modtools.ui.components.buttons.FoldedImageButton;
import modtools.ui.content.Content;
import modtools.utils.ElementUtils;
import modtools.utils.ui.search.FilterTable;

public class Executor extends Content {
	public Executor() {
		super("executor");
	}

	public  Window ui;
	/** @see OK#code() */
	private int    statusCode = ~(1 << 3);

	public void load() {
	}
	FilterTable<Intp> p;
	public void loadUI() {
		ui = new Window(localizedName(), 200, 100, true);
		ui.cont.button(Icon.refresh, Styles.flati, () -> {
			p.clear();
			build(p);
			p.invalidateHierarchy();
		}).size(72, 42);
		ElementUtils.addCodedBtn(ui.cont, "status", 1,
		 i -> statusCode = i, () -> statusCode,
		 StatusEnum.class.getEnumConstants());
		ui.cont.row();
		ui.cont.pane(p = new FilterTable<>(this::build)).colspan(2)
		 .grow();
	}
	public void build(FilterTable<Intp> cont) {
		build(cont, ExecuteTree.roots);
	}
	public void build(FilterTable<Intp> cont, Seq<TaskNode> children) {
		cont.addIntp_UpdateListener(() -> statusCode);
		for (TaskNode node : children) {
			cont.bind(() -> 1 << node.status.code());
			/* 布局
			 * |-------|=========topImg===========|
			 * |  		     |       |        |         |
			 * leftImg | icon  | center | buttons |
			 * |       |       |        |         |
			 * |-------|--------------------------|
			 *  */

			Color color = cont.image().growX().colspan(2).get().color;
			cont.row();
			ImageButton button = new ImageButton(new ImageButtonStyle(Styles.cleari));

			Reflect.set(Element.class,
			 /* leftImage */cont.image().growY().get()
			 , "color", color);
			cont.add(button).growX().marginBottom(8f).marginLeft(6f).row();
			cont.image().color(Color.lightGray).padBottom(6f).growX().colspan(2).row();
			cont.unbind();
			button.getImage().addListener(new IntUI.Tooltip(
			 t -> t.background(Tex.pane).label(() -> node.status.name()).pad(4f)
			));

			button.table(center -> {
				center.left().defaults().left();
				var foldedButton = new FoldedImageButton(true);
				center.table(t -> {
					t.left().defaults().left();
					t.image(node.icon).size(24);
					if (node.repeatCount() < 0 && node.running()) {
						t.image(HopeIcons.loop).color(Pal.accent).size(24);
					}
					t.add(node.name);
					t.add(foldedButton).size(42)
					 .disabled(__ -> node.children.isEmpty());
				}).row();
				center.add("(" + node.source + ")").color(Color.gray).row();
				FilterTable<Intp> table = new FilterTable<>();
				foldedButton.table = table;
				foldedButton.cell = center.add(table).grow();
				foldedButton.fireCheck(true);
				foldedButton.rebuild = () -> {
					table.clear();
					build(table, node.children);
				};
				foldedButton.rebuild.run();
			}).grow();
			button.addListener(new InputListener() {
				public void enter(InputEvent event, float x, float y, int pointer, Element fromActor) {
					event.stop();
				}
			});
			button.table(right -> {
				right.right().defaults().right();
				if (node.isResubmitted()) {
					right.button(Icon.wrench, Styles.squarei, 24, node::edit).size(42);
					right.button(HopeIcons.loop, Styles.squarei, 24, () -> {
						node.forever().apply();
					}).size(42).row();
					right.button(Icon.box, Styles.squarei, 24, () -> {
						node.repeatCount(0).apply();
					}).size(42);
					right.button(Icon.trash, Styles.squarei, 24, () -> {
						node.clear();
						cont.getCells().remove(cont.getCell(button));
						button.remove();
					}).size(42).row();
					right.button(HopeIcons.interrupt, Styles.squarei, 24, node::interrupt).size(42).row();
				}
			}).row();

			/* 顺时针 */
			Action action = Actions.rotateTo(-360, 1.2f);
			button.update(() -> {
				button.getImage().setOrigin(Align.center);
				color.set(node.status.color());
				button.getStyle().imageUp = node.status.icon();
				if (node.status instanceof Running) {
					if (!button.getImage().hasActions()) {
						action.restart();
						button.getImage().rotation = 0;
						button.getImage().addAction(action);
					}
				} else {
					button.getImage().rotation = 0;
					button.getImage().removeAction(action);
				}

				/* button.getStyle().up = switch (root.status) {
					case Error error -> Icon.cancel.tint(error.color);
					case OK ok -> Icon.ok.tint(ok.color);
					case Running running -> Icon.refresh.tint(running.color);
					default -> throw new IllegalStateException("Unexpected value: " + root.status);
				}; */
			});
		}
	}
	public void build() {
		if (ui == null) loadUI();
		ui.show();
	}
}
