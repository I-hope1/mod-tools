package modtools.ui.components.review;

import arc.scene.*;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.Table;
import mindustry.gen.Tex;
import modtools.ui.HopeStyles;
import modtools.ui.components.Window;
import modtools.ui.components.Window.IDisposable;
import modtools.ui.content.ui.ReviewElement;

import static modtools.ui.components.review.CellDetailsWindow.getAndAdd;
import static modtools.utils.ui.FormatHelper.fixed;

public class ElementDetailsWindow extends Window implements IDisposable {
	Element element;

	public ElementDetailsWindow(Element element) {
		super("", 20, 160, true);
		ReviewElement.addFocusSource(this, () -> this, () -> element);


		show();
		this.element = element;

		cont.defaults().growX();
		cont.table(setter -> {
			setter.left().defaults().height(32).left();
			setter.add(ReviewElement.floatSetter("x", () -> fixed(element.x), val -> element.x = val)).row();
			setter.add(ReviewElement.floatSetter("y", () -> fixed(element.y), val -> element.y = val)).row();
			setter.add(ReviewElement.floatSetter("Width", () -> fixed(element.getWidth()), element::setWidth)).row();
			setter.add(ReviewElement.floatSetter("Height", () -> fixed(element.getHeight()), element::setHeight)).row();
			setter.add(ReviewElement.floatSetter("PrefWidth", () -> fixed(element.getPrefWidth()), null)).row();
			setter.add(ReviewElement.floatSetter("PrefHeight", () -> fixed(element.getPrefHeight()), null)).row();
			setter.add(ReviewElement.floatSetter("Rotation", () -> fixed(element.getRotation()), element::setRotation)).row();
		}).growX().row();
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
				getAndAdd(t, cl, "padTop");
				t.add().row();
				getAndAdd(t, cl, "padLeft");
				t.image();
				getAndAdd(t, cl, "padRight").row();
				t.add();
				getAndAdd(t, cl, "padBottom");
				t.add();
			}).colspan(2).row();
			table.defaults().height(32).growX();
			table.button("GrowX", style, cl::growX);
			table.button("GrowY", style, cl::growY);
			table.row();
		}

		CellDetailsWindow.checkboxField(table, Element.class, element, "fillParent", boolean.class);
		CellDetailsWindow.checkboxField(table, Element.class, element, "visible", boolean.class).row();
		if (element instanceof Group)
			CellDetailsWindow.checkboxField(table, Group.class, element, "transform", boolean.class).row();

		cont.row().defaults().height(32).growX();
		cont.button("Invalidate", style, element::invalidate).row();
		cont.button("InvalidateHierarchy", style, element::invalidateHierarchy).row();
		cont.button("Layout", style, element::layout).row();
		cont.button("Pack", style, element::pack).row();
	}
}
