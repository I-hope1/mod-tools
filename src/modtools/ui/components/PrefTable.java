package modtools.ui.components;

import arc.func.*;
import arc.scene.style.Drawable;
import arc.scene.ui.layout.Table;

import static modtools.utils.Tools.as;

/** 允许设置pref大小  */
public class PrefTable extends Table {
	public PrefTable() {
	}
	public PrefTable(Drawable background) {
		super(background);
	}
	public PrefTable(Drawable background, Cons<PrefTable> cons) {
		super(background, as(cons));
	}
	public PrefTable(Cons<PrefTable> cons) {
		super((Cons) cons);
	}

	public FloatFloatf xp, yp;
	public float getPrefWidth() {
		return xp != null ? xp.get(super.getPrefWidth()) : super.getPrefWidth();
	}
	public float getPrefHeight() {
		return yp != null ? yp.get(super.getPrefHeight()) : super.getPrefHeight();
	}
}
