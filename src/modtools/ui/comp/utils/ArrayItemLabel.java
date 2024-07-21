package modtools.ui.comp.utils;

import arc.struct.Seq;
import mindustry.gen.Icon;
import modtools.ui.comp.input.JSRequest;
import modtools.ui.menu.MenuItem;

import java.lang.reflect.Array;

public class ArrayItemLabel<T> extends ValueLabel {
	Object arr;
	int    i;
	public ArrayItemLabel(Class<T> type, Object arr, int i) {
		super(type);
		this.arr = arr;
		this.i = i;
		flushVal();
	}
	public Seq<MenuItem> getMenuLists() {
		Seq<MenuItem> list = new Seq<>();
		basicMenuLists(list);
		list.add(MenuItem.with("selection.set", Icon.editSmall, "@selection.reset", () -> {
			JSRequest.requestForField(val, arr, o -> setNewVal(type.cast(o)));
		}));
		return list;
	}
	public void flushVal() {
		setVal(Array.get(arr, i));
	}
	public void setNewVal(Object newVal) {
		Array.set(arr, i, newVal);
	}
	public Object getObject() {
		return arr;
	}
	public boolean readOnly() {
		return false;
	}
}
