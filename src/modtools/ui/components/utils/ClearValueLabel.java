package modtools.ui.components.utils;

import arc.func.Prov;

public class ClearValueLabel<T> extends ReadOnlyValueLabel<T> {

	Runnable clear;
	public ClearValueLabel(Class<T> type, Prov<T> prov, Runnable clear) {
		super(type, prov);
		this.clear = clear;
	}

	public void clearVal() {
		clear.run();
	}
}
