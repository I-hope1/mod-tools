package modmake.ui.change;

import arc.scene.Element;
import arc.scene.Group;
import mindustry.Vars;

import java.lang.reflect.Field;

public class cMenu {
	public static void main() {
		Group group = (Group) Vars.ui.menuGroup.getChildren().get(0);
		var children = group.getChildren();
		children.get(0).remove();
		cMenuRenderer renderer = new cMenuRenderer();
		Element e = new Element() {
			@Override
			public void draw() {
				renderer.render();
			}
		};
		e.setFillParent(true);
		group.addChildAt(0, e);
	}

	public static class IntField {
		public Field field;

		public IntField(Class<?> clazz, String name) throws NoSuchFieldException {
			field = clazz.getDeclaredField(name);
			field.setAccessible(true);
		}

		public Object get(Object obj) throws IllegalAccessException {
			return field.get(obj);
		}

		public void set(Object obj, Object value) throws IllegalAccessException {
			field.set(obj, value);
		}
	}
}
