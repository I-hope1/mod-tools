package modtools.ui.components.review;

import arc.scene.*;
import arc.scene.ui.layout.Table;
import mindustry.gen.Tex;
import mindustry.ui.Styles;
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
			setter.defaults().height(42).growX();
			setter.add(ReviewElement.floatSetter("x", () -> fixed(element.x), val -> element.x = val)).row();
			setter.add(ReviewElement.floatSetter("y", () -> fixed(element.y), val -> element.y = val)).row();
			setter.add(ReviewElement.floatSetter("width", () -> fixed(element.getWidth()), element::setWidth)).row();
			setter.add(ReviewElement.floatSetter("height", () -> fixed(element.getHeight()), element::setHeight)).row();
			setter.add(ReviewElement.floatSetter("prefWidth", () -> fixed(element.getPrefWidth()), null)).row();
			setter.add(ReviewElement.floatSetter("preHeight", () -> fixed(element.getPrefHeight()), null)).row();
			setter.add(ReviewElement.floatSetter("rot", () -> fixed(element.getRotation()), element::setRotation)).row();
		}).growX().row();
		Table table = cont.table().get();
		table.defaults().growX();
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
			table.button("growX", Styles.flatBordert, cl::growX);
			table.button("growY", Styles.flatBordert, cl::growY);
			table.row();
		}

		CellDetailsWindow.checkboxField(table, Element.class, element, "fillParent", boolean.class);
		CellDetailsWindow.checkboxField(table, Element.class, element, "visible", boolean.class).row();
		if (element instanceof Group)
			CellDetailsWindow.checkboxField(table, Group.class, element, "transform", boolean.class).row();

		cont.row().defaults().height(32).growX();
		cont.button("invalidate", Styles.flatBordert, element::invalidate).row();
		cont.button("invalidateHierarchy", Styles.flatBordert, element::invalidateHierarchy).row();
		cont.button("layout", Styles.flatBordert, element::layout).row();
		cont.button("pack", Styles.flatBordert, element::pack).row();
	}
}
