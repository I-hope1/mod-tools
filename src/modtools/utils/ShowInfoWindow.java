package modtools.utils;

import modtools.ui.components.Window;
import modtools.ui.components.Window.DisposableWindow;
import modtools.utils.JSFunc.*;

class ShowInfoWindow extends DisposableWindow {

	final Class<?> clazz;
	ReflectTable
			fieldsTable,
			methodsTable,
			classesTable;

	public ShowInfoWindow(Class<?> clazz) {
		super(clazz.getSimpleName(), 200, 200, true);
		this.clazz = clazz;
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
