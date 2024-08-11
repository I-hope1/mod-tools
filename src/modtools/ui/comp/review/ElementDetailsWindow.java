package modtools.ui.comp.review;

import arc.func.*;
import arc.scene.*;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.Table;
import mindustry.gen.Tex;
import modtools.ui.HopeStyles;
import modtools.ui.comp.Window;
import modtools.ui.comp.Window.IDisposable;
import modtools.content.ui.ReviewElement;

import static modtools.ui.comp.review.CellDetailsWindow.buildSetter;
import static modtools.utils.ui.FormatHelper.fixed;

public class ElementDetailsWindow extends Window implements IDisposable {
	Element element;

	public ElementDetailsWindow(Element element) {
		super("", 20, 160, true);
		ReviewElement.addFocusSource(this, () -> this, () -> element);

		show();
		this.element = element;

		cont.defaults().growX();
		cont.table(prop -> {
			prop.left().defaults().height(32).left();

			Cons3<String, Floatp, Floatc> c3 = (label, getter, floatc) ->
			 prop.add(ReviewElement.floatSetter(label, () -> fixed(getter.get()), floatc)).row();
			c3.get("x", () -> element.x, val -> element.x = val);
			c3.get("y", () -> element.y, val -> element.y = val);
			c3.get("Width", element::getWidth, element::setWidth);
			c3.get("Height", element::getHeight, element::setHeight);
			c3.get("PrefWidth", element::getPrefWidth, null);
			c3.get("PrefHeight", element::getPrefHeight, null);
			c3.get("Rotation", element::getRotation, element::setRotation);
		}).growX().pad(6, 8, 8, 6).row();

		Table table = cont.table().get();
		table.defaults().growX();
		TextButtonStyle style = HopeStyles.flatBordert;
		l:
		if (element.parent instanceof Table) {
			var cl = ((Table) element.parent).getCell(element);
			if (cl == null) break l;
			table.table(Tex.pane, t -> {
				t.center().defaults().grow().center();
				t.add();
				buildSetter(t, cl, "padTop");
				t.add().row();
				buildSetter(t, cl, "padLeft");
				t.image();
				buildSetter(t, cl, "padRight").row();
				t.add();
				buildSetter(t, cl, "padBottom");
				t.add();
			}).colspan(2).row();

			table.defaults().height(32).growX();
			table.button("GrowX", style, cl::growX);
			table.button("GrowY", style, cl::growY);
			table.row();
		}

		CellDetailsWindow.checkboxField(table, Element.class, element, "fillParent");
		CellDetailsWindow.checkboxField(table, Element.class, element, "visible").row();
		if (element instanceof Group)
			CellDetailsWindow.checkboxField(table, Group.class, element, "transform").row();

		cont.defaults().height(32).growX();
		cont.row().button("Invalidate", style, element::invalidate);
		cont.row().button("InvalidateHierarchy", style, element::invalidateHierarchy);
		cont.row().button("Layout", style, element::layout);
		cont.row().button("Pack", style, element::pack);
	}
}
