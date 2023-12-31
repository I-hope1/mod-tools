package modtools.ui.components.utils;

import arc.struct.Seq;
import arc.util.Align;
import modtools.ui.menus.MenuList;

import java.lang.reflect.*;

public class MethodValueLabel extends ValueLabel {

	Object obj;
	public Object getObject() {
		return obj;
	}
	public MethodValueLabel(Object obj, Method method) {
		super(method.getReturnType());
		this.obj = obj;
		labelAlign = Align.left;
		lineAlign = Align.topLeft;
		isStatic = Modifier.isStatic(method.getModifiers());
		clearVal();
		update(null);
	}
	public Seq<MenuList> getMenuLists() {
		return basicMenuLists(new Seq<>());
	}
	public void setVal() {
		setVal(unset);
	}

	public boolean enabledUpdateMenu() {
		return false;
	}
}
