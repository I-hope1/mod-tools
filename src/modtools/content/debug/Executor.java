package modtools.content.debug;

import arc.func.*;
import arc.graphics.Color;
import arc.scene.*;
import arc.scene.actions.Actions;
import arc.scene.event.*;
import arc.scene.style.Drawable;
import arc.scene.ui.ImageButton;
import arc.scene.ui.ImageButton.ImageButtonStyle;
import arc.struct.Seq;
import arc.util.*;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.content.Content;
import modtools.events.*;
import modtools.events.ExecuteTree.*;
import modtools.events.TaskNode.JSRun;
import modtools.struct.*;
import modtools.ui.*;
import modtools.ui.comp.Window;
import modtools.ui.comp.buttons.FoldedImageButton;
import modtools.ui.comp.input.JSRequest;
import modtools.ui.gen.HopeIcons;
import modtools.ui.menu.*;
import modtools.utils.JSFunc;
import modtools.utils.search.FilterTable;
import modtools.utils.ui.ReflectTools;
import rhino.BaseFunction;

public class Executor extends Content {
	public Executor() {
		super("executor", Icon.wrenchSmall);
	}

	public  Window ui;
	/** @see OK#code() */
	private int    statusCode = -1;

	public void load() {
	}
	FilterTable<Intp> p;
	public void loadUI() {
		ui = new Window(localizedName(), 200, 100, true);
		ui.cont.button(Icon.refresh, HopeStyles.flati, () -> {
			p.clear();
			build(p);
			p.invalidateHierarchy();
		}).size(72, 42);
		ReflectTools.addCodedBtn(ui.cont, "status", 1,
		 i -> statusCode = i, () -> statusCode,
		 StatusList.values());
		ui.cont.button("@task.newtask", Icon.addSmall, HopeStyles.flatt, () -> {
			JSRequest.requestCode(code -> ExecuteTree.context(customTask.get(), () -> {
				BaseFunction scope = new BaseFunction(JSRequest.topScope, null);
				ExecuteTree.node("custom",
					new JSRun(code, scope))
				 .code(code)
				 .resubmitted().apply();
			}));
		}).size(120, 45);
		ui.cont.row();
		ui.cont.pane(p = new FilterTable<>(this::build))
		 .colspan(3)
		 .grow();
	}
	public void build(FilterTable<Intp> cont) {
		build(cont, ExecuteTree.roots);
	}
	public void build(FilterTable<Intp> cont, MySet<TaskNode> children) {
		cont.top().defaults().top();
		cont.addUpdateListenerIntp(() -> statusCode);
		for (TaskNode node : children) {
			cont.bind(() -> 1 << node.status.code());
			/* 布局
			 * |-------|=========topImg===========|
			 * |       |       |        |         |
			 * leftImg | icon  | center | buttons |
			 * |       |       |        |         |
			 * |-------|--------------------------|
			 *  */

			Color color = cont.image().growX().colspan(2).get().color;
			cont.row();
			ImageButton button = new ImageButton(new ImageButtonStyle(Styles.cleari) {{
				down = over;
			}});

			Reflect.set(Element.class,
			 /* leftImage */cont.image().growY().get()
			 , "color", color);
			cont.add(button).growX().marginBottom(8f).marginLeft(6f);
			Reflect.set(Element.class,
			 /* rightImage */cont.image().growY().get()
			 , "color", color);
			cont.row();
			Reflect.set(Element.class,
			 /* bottomImage */cont.image().padBottom(6f).growX().colspan(2).get()
			 , "color", color);
			cont.row();
			cont.unbind();
			IntUI.addTooltipListener(button.getImage(), node.status.name());

			button.table(center -> {
				center.marginLeft(4f);
				center.left().defaults().left();
				var foldedButton = new FoldedImageButton(true);
				center.table(t -> {
					t.left().defaults().left();
					if (node.icon != null) t.image(node.icon).size(24).padLeft(6f).padRight(6f);
					if (node.repeatCount() < 0 && node.running()) {
						t.image(HopeIcons.loop).color(Pal.accent).size(24);
					}
					t.add(node.name);
					t.add(foldedButton).size(42)
					 .disabled(_ -> node.children.isEmpty());
				}).row();
				center.add(STR."(\{node.source})").color(Color.gray).row();
				FilterTable<Intp> table = new FilterTable<>();
				foldedButton.setContainer(center.add(table).grow());
				foldedButton.rebuild = () -> {
					table.clear();
					build(table, node.children);
				};
				foldedButton.fireCheck(true);
				// foldedButton.rebuild.run();
			}).grow();
			button.addListener(new InputListener() {
				public void enter(InputEvent event, float x, float y, int pointer, Element fromActor) {
					event.stop();
				}
			});
			MenuBuilder.addShowMenuListenerp(button, () -> (node.menuList != null ? node.menuList.get() : new Seq<MenuItem>())
			 .add(node.code == null ? null : MenuItem.with("copy.code", Icon.copySmall, "cpy code", () -> {
				 if (node.code != null) JSFunc.copyText(node.code);
			 })));
			button.table(right -> {
				if (node.isResubmitted()) {
					right.right().defaults().right().size(42);
					Cons3<Drawable, Runnable, String> cons3 = (icon, r, tipKey) ->
					 right.button(icon, Styles.squarei, 24, r).tooltip(tipKey(tipKey));

					cons3.get(Icon.wrench, node::edit, "edit");
					cons3.get(HopeIcons.loop, () -> node.forever().apply(), "loop");
					right.row();

					cons3.get(Icon.box, () -> node.repeatCount(0).apply(), "exec");
					cons3.get(Icon.trash, () -> {
						node.clear();
						cont.getCells().remove(cont.getCell(button));
						button.remove();
					}, "remove");
					right.row();

					cons3.get(HopeIcons.interrupt, node::interrupt, "interrupt");
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
	private static final LazyValue<TaskNode> customTask = LazyValue.of(() -> ExecuteTree.nodeRoot(null, "CustomJS", "startup",
	 Icon.craftingSmall, IntVars.EMPTY_RUN));
	public void build() {
		if (ui == null) loadUI();
		ui.show();
	}
}
