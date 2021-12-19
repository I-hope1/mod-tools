package modmake.ui;

import arc.*;
import arc.func.*;
import arc.math.*;
import arc.scene.*;
import arc.scene.actions.*;
import arc.scene.event.ClickListener;
import arc.scene.event.InputEvent;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;

import mindustry.ctype.UnlockableContent;
import mindustry.gen.*;
import mindustry.ui.*;

public class IntUI {

	// 长按事件
	public static <T extends Element> T longPress(T elem, float duration, Cons<Boolean> func) {
		elem.addListener(new ClickListener() {
			public void clicked(InputEvent event, float x, float y) {
				func.get(Time.millis() - visualPressedTime > duration);
			}
		});

		return elem;
	}

	/**
	 * 弹出一个小窗，自己设置内容
	 * 
	 * @param button     用于定位弹窗的位置
	 * @param f
	 **                   p 是Table，你可以添加元素
	 **                   hide 是一个函数，调用就会关闭弹窗
	 **                   text 如果 @param 为 true ，则启用。用于返回用户在搜索框输入的文本
	 * @param searchable 可选，启用后会添加一个搜索框
	 */
	public static <T extends Button> Table showSelectTable(T button, Cons3<Table, Runnable, String> f,
			Boolean searchable) {
		if (button == null)
			throw new NullPointerException("button cannot be null");
		Table t = new Table(Tex.button) {
			public float getPrefHeight() {
				return Math.min(super.getPrefHeight(), Core.graphics.getHeight());
			}

			public float getPrefWidth() {
				return Math.min(super.getPrefWidth(), Core.graphics.getWidth());
			}
		};
		Element hitter = new Element();
		Runnable hide = () -> {
			hitter.remove();
			t.actions(Actions.fadeOut(0.3f, Interp.fade), Actions.remove());
		};
		hitter.clicked(hide);
		hitter.fillParent = true;

		Core.scene.add(hitter);
		Core.scene.add(t);

		t.update(() -> {
			if (button.parent == null || !button.isDescendantOf(Core.scene.root)) {
				Core.app.post(hide);
				return;
			}

			button.localToStageCoordinates(Tmp.v1.set(button.getWidth() / 2f, button.getHeight() / 2f));
			t.setPosition(Tmp.v1.x, Tmp.v1.y, Align.center);
			if (t.getWidth() > Core.scene.getWidth())
				t.setWidth(Core.graphics.getWidth());
			if (t.getHeight() > Core.scene.getHeight())
				t.setHeight(Core.graphics.getHeight());
			t.keepInStage();
			t.invalidateHierarchy();
			t.pack();
		});
		t.actions(Actions.alpha(0), Actions.fadeIn(0.3f, Interp.fade));

		Table p = new Table();
		p.top();
		if (searchable) {
			t.table(top -> {
				top.image(Icon.zoom);
				TextField text = new TextField();
				top.add(text).fillX();
				text.changed(() -> f.get(p, hide, text.getText()));
				// /* 自动聚焦到搜索框 */
				// text.fireClick();
			}).padRight(8f).fillX().fill().top().row();
		}
		f.get(p, hide, "");

		ScrollPane pane = new ScrollPane(p);
		t.top().add(pane).pad(0f).top();
		pane.setScrollingDisabled(true, false);

		t.pack();

		return t;
	}

	public static <T extends Button> Table showSelectListTable(T button, Seq<String> list, Prov<String> holder,
			Cons<String> cons,
			int width, int height, Boolean searchable) {
		return showSelectTable(button, (Table p, Runnable hide, String text) -> {
			p.clearChildren();

			for (String item : list) {
				p.button(item, Styles.cleart, () -> {
					cons.get(item);
					hide.run();
				}).size(width, height).disabled(holder.get() == item).row();
			}
		}, searchable);
	}

	/**
	 * 弹出一个可以选择内容的窗口（类似物品液体源的选择）
	 * （需要提供图标）
	 * 
	 * @param items     用于展示可选的内容
	 * @param icons     可选内容的图标
	 * @param holder    选中的内容，null就没有选中任何
	 * @param size      每个内容的元素大小
	 * @param imageSize 每个内容的图标大小
	 * @param cons      选中内容就会调用
	 * @param cols      一行的元素数量
	 */
	public static <T extends Button, T1> Table showSelectImageTableWithIcons(T button,
			Seq<T1> items, Seq<? extends TextureRegionDrawable> icons, Prov<T1> holder, Cons<T1> cons,
			float size, float imageSize, int cols,
			boolean searchable) {
		return showSelectTable(button, (Table p, Runnable hide, String text) -> {
			p.clearChildren();
			p.left();
			ButtonGroup<ImageButton> group = new ButtonGroup<>();
			group.setMinCheckCount(0);
			p.defaults().size(size);

			for (int i = 0; i < items.size; i++) {
				T1 item = items.get(i);
				// 过滤不满足条件的
				if (text != "" && !(item instanceof String && ((String) item).matches(text)) &&
						!(item instanceof UnlockableContent && (((UnlockableContent) item).name.matches(text) ||
								((UnlockableContent) item).localizedName.matches(text))))
					continue;

				ImageButton btn = p.button(Tex.whiteui, Styles.clearToggleTransi, imageSize, () -> {
					cons.get(item);
					hide.run();
				}).size(size).get();
				btn.getStyle().imageUp = icons.get(i);
				btn.update(() -> button.setChecked(holder.get() == item));

				if ((i + 1) % cols == 0)
					p.row();
			}
		}, searchable);
	}

	/**
	 * 弹出一个可以选择内容的窗口（无需你提供图标）
	 */
	public static <T extends Button, T1 extends UnlockableContent> Table showSelectImageTable(T button,
			Seq<T1> items, Prov<T1> holder, Cons<T1> cons, float size, int imageSize, int cols,
			boolean searchable) {
		Seq<TextureRegionDrawable> icons = new Seq<>();
		items.each(item -> icons.add(new TextureRegionDrawable(item.icon(Cicon.small))));
		return showSelectImageTableWithIcons(button, items, icons, holder, cons, size, imageSize, cols,
				searchable);
	}
}