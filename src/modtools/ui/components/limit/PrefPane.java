package modtools.ui.components.limit;

import arc.func.*;
import arc.scene.Element;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;

public class PrefPane extends ScrollPane {
	public PrefPane(Element widget) {
		super(widget);
	}
	public PrefPane(Cons<Table> cons, FloatFloatf xp) {
		this(cons);
		this.xp = xp;
	}
	public PrefPane(Cons<Table> cons) {
		super(new Table(cons));
	}
	public PrefPane(Element widget, ScrollPaneStyle style) {
		super(widget, style);
	}

	public FloatFloatf xp, yp;
	public float getPrefWidth() {
		return xp != null ? xp.get(super.getPrefWidth()) : super.getPrefWidth();
	}
	public float getPrefHeight() {
		return yp != null ? yp.get(super.getPrefHeight()) : super.getPrefHeight();
	}
}
