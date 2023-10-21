package modtools.ui.components.utils;

import arc.func.*;
import arc.struct.Seq;
import modtools.events.E_JSFunc;
import modtools.ui.IntUI.MenuList;

public class ReadOnlyValueLabel<T> extends ValueLabel {
	Prov<T> prov;
	public Object getObject() {
		return null;
	}
	public ReadOnlyValueLabel(Class<T> type, Prov<T> prov) {
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
		return basicMenuLists(new Seq<>());
	}
	public void setVal() {
		if (prov == null) return;
		setVal(prov.get());
	}
}
