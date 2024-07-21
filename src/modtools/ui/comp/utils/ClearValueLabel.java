package modtools.ui.comp.utils;

import arc.func.*;
import arc.scene.Element;
import arc.struct.Seq;
import modtools.ui.menu.MenuItem;

public class ClearValueLabel<T> extends PlainValueLabel<T> {

	final  Runnable clear;
	public Cons<T>  setter;
	public ClearValueLabel(Class<T> type, Prov<T> prov, Runnable clear) {
		super(type, prov);
		this.clear = clear;
	}
	public void clearVal() {
		if (clear != null) clear.run();
	}
	public Seq<MenuItem> getMenuLists() {
		return super.getMenuLists();
	}
	protected void elementSetter(Seq<MenuItem> list, Cons<Element> callback) {
		super.elementSetter(list, (Cons<Element>) setter);
	}
}
