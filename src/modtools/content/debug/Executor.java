package modtools.content.debug;

import arc.func.*;
import arc.graphics.Color;
import arc.scene.*;
import arc.scene.actions.RepeatAction;
import arc.scene.actions.RotateByAction;
import arc.scene.event.*;
import arc.scene.style.Drawable;
import arc.scene.ui.Image;
import arc.scene.ui.ImageButton;
import arc.scene.ui.ImageButton.ImageButtonStyle;
import arc.struct.Seq;
import arc.util.*;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.WorldSaver.*;
import modtools.content.Content;
import modtools.events.*;
import modtools.events.ExecuteTree.*;
import modtools.events.TaskNode.JSRun;
import modtools.events.TaskNode.JSRun.CScope;
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

public class Executor extends Content {
	public Executor() {
		super("executor", Icon.wrenchSmall);
		alwaysLoad = true;
	}

	public  Window ui;
	/** @see OK#code() */
	private int    statusCode = -1;

	FilterTable<Intp> p;
	/** 上次重建时的树结构版本，用于判断是否需要自动重建列表 */
	private int builtStructureVersion = Integer.MIN_VALUE;
	public void loadUI() {
		ui = new IconWindow(200, 100, true);
		ui.cont.button(Icon.refresh, HopeStyles.flati, this::refreshAll).size(72, 42);
		ReflectTools.addCodedBtn(ui.cont, "status", 1,
		 i -> statusCode = i, () -> statusCode,
		 StatusList.values());
		ui.cont.button("@task.newtask", Icon.addSmall, HopeStyles.flatt, () -> {
			JSRequest.requestCode(CScope.tester.scope(), code -> ExecuteTree.context(customTask.get(), () -> {
				ExecuteTree.node("custom",
					new JSRun(code, CScope.tester))
				 .code(code)
				 .resubmitted().apply();
				refreshAll();
			}));
		}).size(120, 45);
		ui.cont.row();
		ui.cont.pane(p = new FilterTable<>(this::build))
		 .colspan(3)
		 .grow();
		builtStructureVersion = ExecuteTree.structureVersion;
		// 面板打开时，树结构变化（新增/删除任务）立即反映到列表
		ui.update(() -> {
			if (ExecuteTree.structureVersion != builtStructureVersion) refreshAll();
		});
	}
	/** 清空并重建整个任务树列表；过滤条件由 build() 重新安装 */
	private void refreshAll() {
		if (p == null) return;
		p.clear();
		build(p);
		p.invalidateHierarchy();
		builtStructureVersion = ExecuteTree.structureVersion;
	}
	public void build(FilterTable<Intp> cont) {
		build(cont, ExecuteTree.roots);
	}
	public void build(FilterTable<Intp> cont, MySet<TaskNode> children) {
		cont.top().defaults().top();
		cont.setConditionUpdateListener(new StatusFilter());
		for (TaskNode node : children) {
			cont.bind(() -> node.status().bit());
			/* 布局
			 * |-------|=========topImg===========|
			 * |       |       |        |         |
			 * leftImg | icon  | center | buttons |
			 * |       |       |        |         |
			 * |-------|--------------------------|
			 *  */

			Image topLine = cont.image().growX().colspan(2).get();
			cont.row();
			ImageButtonStyle style = new ImageButtonStyle(Styles.cleari);
			style.down = style.over;
			ImageButton button = new ImageButton(style);

			Image leftLine = cont.image().growY().get();
			cont.add(button).growX().marginBottom(8f).marginLeft(6f);
			Image rightLine = cont.image().growY().get();
			cont.row();
			Image bottomLine = cont.image().padBottom(6f).growX().colspan(2).get();
			cont.row();
			cont.unbind();
			Image[] lines = {topLine, leftLine, rightLine, bottomLine};
			Image image = button.getImage();
			image.setOrigin(Align.center);
			IntUI.addTooltipListener(image, () -> node.status().name());

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
				center.add("(" + node.source + ")").color(Color.gray).row();
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
			 })).add(node.code == null ? null :
				DisabledList.withd("save.map", Icon.copySmall, "save code in map", () -> !Vars.state.isGame() || Vars.state.map == null,() -> {
					MyCustomChunk.instance.data.add(new Pair<>(DataType.code, node.code));
					IntUI.showInfoFade("Saved.");
			 })));
			if (node.isResubmitted()) {
				button.table(right -> {
					right.right().defaults().right().size(42);
					Cons3<Drawable, Runnable, String> cons3 = (icon, r, tipKey) ->
					 right.button(icon, Styles.squarei, 24, r).tooltip(tipKey(tipKey));

					cons3.get(Icon.wrench, node::edit, "edit");
					cons3.get(HopeIcons.loop, () -> node.forever().apply(), "loop");
					right.row();

					cons3.get(Icon.box, () -> node.repeatCount(0).apply(), "exec");
					cons3.get(Icon.trash, () -> {
						node.clear();
						refreshAll();
					}, "remove");
					right.row();

					cons3.get(HopeIcons.interrupt, node::interrupt, "interrupt");
				}).row();
			}

			/* 顺时针 */
			Status[] last = {node.status()};
			applyStatus(last[0], button, image, lines);
			button.update(() -> {
				Status current = node.status();
				if (current != last[0]) {
					last[0] = current;
					applyStatus(current, button, image, lines);
				} else if (current instanceof Running) {
					// 尺寸在布局之后才确定；Running 期间校正旋转中心，避免绕左上角转
					image.setOrigin(Align.center);
				}
			});
		}
	}
	/** 状态变化时才更新颜色/图标/旋转 action，避免每帧写样式 */
	private static void applyStatus(Status status, ImageButton button, Image image, Image[] lines) {
		Color color = status.color();
		for (Image line : lines) line.setColor(color);
		button.getStyle().imageUp = status.icon();
		image.clearActions();
		image.setOrigin(Align.center);
		image.setRotation(0);
		if (status instanceof Running) {
			// 不用 Actions.rotateBy/forever：它们是池化对象，clearActions() 会归还池，
			// 之后不可再复用；这里每轮 Running 都新建非池化 action。
			RotateByAction rotate = new RotateByAction();
			rotate.setAmount(-360);
			rotate.setDuration(1.2f);
			RepeatAction spin = new RepeatAction();
			spin.setCount(RepeatAction.FOREVER);
			spin.setAction(rotate);
			image.addAction(spin);
		}
	}
	/** statusCode 或任一节点状态变化时重新过滤；FilterTable 自身会跳过已经匹配的 cell */
	private final class StatusFilter implements FilterTable.Condition<Intp> {
		private int lastStatus = Integer.MIN_VALUE, lastVersion = Integer.MIN_VALUE;
		public boolean needUpdate() {
			int status = statusCode, version = ExecuteTree.statusVersion;
			if (status == lastStatus && version == lastVersion) return false;
			lastStatus = status;
			lastVersion = version;
			return true;
		}
		public boolean valid(Intp name) {
			return (statusCode & name.get()) != 0;
		}
	}
	private static final LazyValue<TaskNode> customTask = LazyValue.of(() -> ExecuteTree.nodeRoot(null, "CustomJS", "startup",
	 Icon.craftingSmall, IntVars.EMPTY_RUN));
	public void build() {
		if (ui == null) loadUI();
		else refreshAll();
		ui.show();
	}
}
