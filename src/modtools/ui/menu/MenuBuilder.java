package modtools.ui.menu;

import arc.Core;
import arc.func.Prov;
import arc.scene.Element;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.*;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.pooling.*;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.ui.IntUI;
import modtools.ui.IntUI.SelectTable;
import modtools.utils.ArrayUtils.DisposableSeq;
import modtools.utils.EventHelper;

import static modtools.IntVars.mouseVec;
import static modtools.utils.ElementUtils.findClosestPane;
import static modtools.utils.ui.CellTools.rowSelf;

public class MenuBuilder {
	/**
	 * Add show menu listener.
	 * @param elem 元素
	 * @param prov menu提供者
	 */
	public static void
	addShowMenuListenerp(Element elem, Prov<Seq<MenuItem>> prov) {
		EventHelper.longPressOrRclick(elem, _ -> showMenuListDispose(prov));
	}
	/**
	 * Dispose after close.
	 * @param prov menu提供者
	 */
	public static void showMenuListDispose(Prov<Seq<MenuItem>> prov) {
		Seq<MenuItem> list = prov.get();
		showMenuList(list, () -> {
			Pools.freeAll(list, false);
			if (list instanceof DisposableSeq) Pools.free(list);
		});
	}
	/**
	 * @param list 关闭后自动销毁
	 */
	public static void
	addShowMenuListener(Element elem, MenuItem... list) {
		EventHelper.longPressOrRclick(elem, _ -> {
			showMenuList(Seq.with(list));
		});
	}
	public static void
	addShowMenuListener(Element elem, Iterable<MenuItem> list) {
		EventHelper.longPressOrRclick(elem, _ -> showMenuList(list));
	}
	public static void showMenuList(Iterable<MenuItem> list) {
		showMenuList(list, null);
	}
	public static void showMenuList(Iterable<MenuItem> list, Runnable hiddenListener) {
		IntUI.showSelectTableRB(mouseVec.cpy(), (p, hide, _) -> {
			showMenuList(list, hiddenListener, p, hide);
		}, false);
	}
	public static SelectTable showMenuListFor(
	 Element elem,
	 int align, Prov<Seq<MenuItem>> prov) {
		return IntUI.showSelectTable(elem, (p, hide, _) -> {
			Seq<MenuItem> list = prov.get();
			showMenuList(list, freeAllMenu(list), p, hide);
		}, false, align);
	}
	public static Runnable freeAllMenu(Seq<MenuItem> list) {
		return () -> Pools.freeAll(list, false);
	}
	/**
	 * 自动过滤掉{@code null}
	 * TODO: 多个FoldedList有问题 */
	public static Cell<ScrollPane> showMenuList(
	 Iterable<MenuItem> list, Runnable hiddenListener,
	 Table p, Runnable hideRun) {
		{// 修改p
			ScrollPane pane = findClosestPane(p);
			if (pane != null) {
				p = (Table) pane.parent;
			}
		}

		Table main = new Table();

		for (var menu : list) {
			/* 过滤掉null */
			if (menu == null) continue;

			var cell = rowSelf(main.button(menu.getName(), menu.icon, menu.style(),
				menu.iconSize(), IntVars.EMPTY_RUN
			 ).minSize(Float.NEGATIVE_INFINITY, IntUI.FUNCTION_BUTTON_SIZE)
			 .growX().left()
			 .padTop(-1)
			 .marginLeft(5f).marginRight(5f)
			 .wrapLabel(false));
			// cell.get().getLabel().setFontScale(0.9f);
			cell.get().getLabelCell().padLeft(8f).labelAlign(Align.left);

			menu.build(p, cell, () -> {
				hideRun.run();
				if (hiddenListener != null) hiddenListener.run();
			});
		}
		main.pack();

		Cell<ScrollPane> cell = p.pane(Styles.smallPane, main).growY();
		cell.get().setOverscroll(false, false);
		return cell;
	}
	/**
	 * Menu `Copy ${key} As Js` constructor.
	 * @param prov 对象提供
	 * @return a menu.
	 */
	@SuppressWarnings("StringTemplateMigration")
	public static MenuItem copyAsJSMenu(String key, Prov<Object> prov) {
		return MenuItem.with(key + ".copy", Icon.copySmall,
		 IntUI.buildStoreKey(key == null ? null : Core.bundle.get("jsfunc." + key, key)),
		 IntUI.storeRun(prov));
	}
	@SuppressWarnings("StringTemplateMigration")
	public static MenuItem copyAsJSMenu(String key, Runnable run) {
		return MenuItem.with(key + ".copy", Icon.copySmall,
		 IntUI.buildStoreKey(key == null ? null : Core.bundle.get("jsfunc." + key, key)),
		 run);
	}
}
