package modtools.ui.comp.utils;

import arc.func.*;
import arc.scene.Element;
import arc.struct.Seq;
import modtools.ui.menu.MenuItem;

import static modtools.utils.Tools.as;

public class ClearValueLabel<T> extends PlainValueLabel<T> {
	final Runnable clear;
	public Cons<T>  setter;
	public Class<? extends Element> elementType = Element.class;
	public ClearValueLabel(Class<T> type, Prov<T> prov, Runnable clear) {
		super(type, prov);
		this.clear = clear;
	}
	public ClearValueLabel(Class<T> type, Prov<T> prov, Runnable clear, Cons<T> setter) {
		super(type, prov);
		this.clear = clear;
		this.setter = setter;
	}
	public void clearVal() {
		if (clear != null) clear.run();
	}
	public Seq<MenuItem> getMenuLists() {
		Seq<MenuItem> lists = super.getMenuLists();
		if (clear == null) lists.remove(k -> k.key.equals(KEY_CLEAR));
		return lists;
	}
	protected <E extends Element> void elementSetter(Seq<MenuItem> list, Class<E> __, Cons<E> callback) {
		super.elementSetter(list, as(elementType), as(setter));
	}
}
