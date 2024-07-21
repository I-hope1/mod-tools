package modtools.ui.comp.utils;

import arc.util.Nullable;

import java.lang.reflect.Modifier;

public abstract class ReflectValueLabel extends ValueLabel {
	public final @Nullable Object obj;
	public final int modifier;
	public final boolean isStatic;
	protected ReflectValueLabel(Class<?> type, Object obj, int modifier) {
		super(type);
		this.obj = obj;
		this.modifier = modifier;
		isStatic = Modifier.isStatic(modifier);
	}
	public Object getObject() {
		return obj;
	}
	public boolean isFinal() {
		return Modifier.isFinal(modifier);
	}
}
