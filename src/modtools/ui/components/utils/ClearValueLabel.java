package modtools.ui.components.utils;

import arc.func.*;
import arc.scene.Element;
import arc.struct.Seq;
import modtools.ui.IntUI.MenuList;

public class ClearValueLabel<T> extends PlainValueLabel<T> {

	Runnable clear;
	public Cons<T> setter;
	public ClearValueLabel(Class<T> type, Prov<T> prov, Runnable clear) {
		super(type, prov);
		this.clear = clear;
	}
	public void clearVal() {
		clear.run();
	}
	public Seq<MenuList> getMenuLists() {
		return super.getMenuLists();
	}
	protected void elementSetter(Seq<MenuList> list, Cons<Element> callback) {
		super.elementSetter(list, (Cons<Element>) setter);
	}
}
