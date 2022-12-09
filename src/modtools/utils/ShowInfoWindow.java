package modtools.utils;

import arc.util.*;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import modtools.ui.components.Window;
import modtools.utils.JSFunc.*;

import java.io.*;

class ShowInfoWindow extends Window {

	final Class<?> clazz;
	ReflectTable
			fieldsTable,
			methodsTable,
			classesTable;

	public ShowInfoWindow(Class<?> clazz) {
		super(clazz.getSimpleName(), 200, 200, true);
		this.clazz = clazz;

		hidden(() -> {
			all.remove(this);
			clearChildren();
			/*fieldsTable.clear();
			methodsTable.clear();
			classesTable.clear();
			fieldsTable = null;
			methodsTable = null;
			classesTable = null;*/
		});
	}

	public void build() {
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() + "#" + title.getText();
	}

	public static String getName(Class<?> cls) {
		while (cls != null) {
			String tmp = cls.getName();
			if (!tmp.isEmpty()) return tmp;
			cls = cls.getSuperclass();
		}
		return "unknown";
	}
}
