package modtools.ui.windows.utils;

import arc.Core;
import arc.func.Prov;
import arc.scene.ui.layout.Table;
import arc.struct.*;
import ihope_lib.MyReflect;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import modtools.ui.IntUI.MenuList;
import modtools.ui.components.Window;
import modtools.ui.components.utils.PlainValueLabel;
import modtools.utils.reflect.*;

import java.lang.reflect.Field;
import java.util.Objects;

public class Comparator extends Window {
	private Comparator() {
		super("Comparator", 100, 100, true, true);
	}
	/**
	 * 比较两个对象的差异
	 *
	 * @param o1 第一个对象
	 * @param o2 第二个对象
	 */
	public static void compare(Object o1, Object o2) {
		Comparator comparator = new Comparator();
		comparator.show();
		if (o1 == o2) {
			comparator.cont.add("Two objects are the same.");
			Core.app.post(comparator::show);
			return;
		}
		if (o1 == null || o2 == null) {
			comparator.cont.add((o1 == null ? "o1" : "o2") + " is null.");
			Core.app.post(comparator::show);
			return;
		}

		Table cont = new Table();
		cont.top().defaults().growX();
		comparator.cont.pane(cont).grow();

		ObjectSet<Class<?>> classes = ClassUtils.getClassAndParents(o1.getClass());
		classes.addAll(ClassUtils.getClassAndParents(o2.getClass()));
		for (Class<?> cls : classes) {
			cont.add("CLAZZ", Pal.surge);
			cont.add(cls.getSimpleName(), cls.isInterface() ? Pal.accentBack : Pal.accent).colspan(2).row();
			cont.image().color(Pal.accent).colspan(3).growX().row();
			for (Field f : cls.getDeclaredFields()) {
				MyReflect.setOverride(f);
				Object v1 = FieldUtils.getOrNull(f, o1),
				 v2 = FieldUtils.getOrNull(f, o2);
				if (Objects.deepEquals(v1, v2)) continue;
				cont.add(f.getName());
				cont.add(new ComparatorLabel(f.getType(), () -> v1, o1, o2, f)).padRight(4f);
				cont.add(new ComparatorLabel(f.getType(), () -> v2, o1, o2, f));
				cont.row();
			}
		}

		Core.app.post(comparator::show);
	}

	public static class ComparatorLabel extends PlainValueLabel {
		Object o1, o2;
		Field field;
		public ComparatorLabel(Class type, Prov prov, Object o1, Object o2, Field field) {
			super(type, prov);
			this.o1 = o1;
			this.o2 = o2;
			this.field = field;
		}
		public Seq<MenuList> getMenuLists() {
			Seq<MenuList> lists = super.getMenuLists();
			lists.add(MenuList.with(Icon.chartBarSmall, "Compare",
			 () -> Comparator.compare(FieldUtils.getOrNull(field, o1), FieldUtils.getOrNull(field, o2))));
			return lists;
		}
	}
	public Window show() {
		super.show();
		display();
		return this;
	}
}
