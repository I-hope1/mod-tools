package modtools.ui.menu;

import arc.Core;
import arc.func.*;
import arc.scene.Element;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.pooling.Pools;
import mindustry.gen.*;
import modtools.ui.IntUI;
import modtools.ui.IntUI.SelectTable;
import modtools.utils.ArrayUtils.DisposableSeq;
import modtools.utils.EventHelper;

import java.util.Objects;

import static modtools.utils.ElementUtils.findClosestPane;

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
	public static <T> void
	addShowMenuListenerp(Element elem, Class<T> target, Func<T, Prov<Seq<MenuItem>>> func) {
		EventHelper.longPressOrRclick(elem, target, t -> {
			if (t != null) showMenuListDispose(func.get(t));
		});
	}
	/**
	 * Dispose after close.
	 * @param prov menu提供者
	 * @return SelectTable nullable
	 */
	public static SelectTable showMenuListDispose(Prov<Seq<MenuItem>> prov) {
		Seq<MenuItem> list = prov.get();
		if (list.find(Objects::nonNull) == null) return null;
		return showMenuList(list, () -> {
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
	public static SelectTable showMenuList(Iterable<MenuItem> list, Runnable hiddenListener) {
		return IntUI.showSelectTableRB((p, hide, _) -> {
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
	 * TODO: 多个FoldedList有问题
	 */
	public static Table showMenuList(
	 Iterable<MenuItem> list, Runnable hiddenListener,
	 Table p, Runnable hideRun) {
		{// 修改p
			ScrollPane pane = findClosestPane(p);
			if (pane != null) {
				p = (Table) pane.parent;
			}
		}
		p.background(Tex.paneSolid);

		for (var menu : list) {
			/* 过滤掉null */
			if (menu == null) continue;

			menu.build(p, () -> {
				hideRun.run();
				if (hiddenListener != null) hiddenListener.run();
			});
		}

		return p;
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
