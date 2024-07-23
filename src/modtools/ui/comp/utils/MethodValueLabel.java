package modtools.ui.comp.utils;

import arc.struct.Seq;
import arc.util.Align;
import modtools.ui.menu.MenuItem;

import java.lang.reflect.Method;

public class MethodValueLabel extends ReflectValueLabel {
	public MethodValueLabel(Object obj, Method method) {
		super(method.getReturnType(), obj, method.getModifiers());
		labelAlign = Align.left;
		lineAlign = Align.topLeft;
		clearVal();
		update(null);
	}
	public Seq<MenuItem> getMenuLists() {
		return basicMenuLists(new Seq<>());
	}
	public void flushVal() {
		setVal(unset);
	}

	public boolean enabledUpdateMenu() {
		return false;
	}
}
