package modtools.ui.comp.utils;

import arc.func.*;
import arc.struct.Seq;
import mindustry.gen.Icon;
import modtools.events.E_JSFunc;
import modtools.ui.menu.MenuItem;

import static modtools.ui.menu.MenuBuilder.copyAsJSMenu;

public class PlainValueLabel<T> extends ValueLabel {
	Prov<T> prov;
	public Object getObject() {
		return null;
	}
	public PlainValueLabel(Class<T> type, Prov<T> prov) {
		super(type);
		this.prov = prov;
		val = unset;
		flushVal();
		if (prov != null) update(() -> {
			if (autoUpdate()) {
				flushVal();
			}
		});
	}
	protected boolean autoUpdate() {
		return E_JSFunc.auto_refresh.enabled() && enableUpdate;
	}

	public static final String KEY_CLEAR = "val.clear";
	public Seq<MenuItem> getMenuLists() {
		Seq<MenuItem> list = new Seq<>();
		specialBuild(list);
		detailsBuild(list);

		list.add(MenuItem.with(KEY_CLEAR, Icon.eraserSmall, "@clear", this::clearVal));

		list.add(copyAsJSMenu("value", () -> val));
		return list;
	}
	public void flushVal() {
		if (prov == null) return;
		setVal(prov.get());
	}
}
