package modtools.ui.components.utils;

import arc.func.*;
import arc.struct.Seq;
import mindustry.gen.Icon;
import modtools.events.E_JSFunc;
import modtools.ui.menu.MenuList;

import static modtools.ui.IntUI.copyAsJSMenu;

public class PlainValueLabel<T> extends ValueLabel {
	Prov<T> prov;
	public Object getObject() {
		return null;
	}
	public PlainValueLabel(Class<T> type, Prov<T> prov) {
		super(type);
		this.prov = prov;
		val = unset;
		setVal();
		if (prov != null) update(() -> {
			if (E_JSFunc.auto_refresh.enabled() && enableUpdate) {
				setVal();
			}
		});
	}

	public Seq<MenuList> getMenuLists() {
		Seq<MenuList> list = new Seq<>();
		specialBuild(list);
		detailsBuild(list);

		list.add(MenuList.with(Icon.eraserSmall, "@clear", this::clearVal));

		list.add(copyAsJSMenu("value", () -> val));
		return list;
	}
	public void setVal() {
		if (prov == null) return;
		setVal(prov.get());
	}
}
