package modtools.ui.comp.input;

import arc.func.Prov;
import arc.scene.ui.Label;

/**
 * a label that disables markup ( [color]文字颜色 )
 */
public class NoMarkupLabel extends Label {
	public NoMarkupLabel(Prov<CharSequence> sup) {
		super(sup);
	}
	public NoMarkupLabel(CharSequence text) {
		super(text);
	}
	public NoMarkupLabel(CharSequence text, LabelStyle style) {
		super(text, style);
	}
	public NoMarkupLabel(float scale) {
		super((CharSequence) null);
		setFontScale(scale);
	}
	{
		layout.ignoreMarkup = true;
	}

	public float getPrefWidth() {
		try {
			prefSizeLayout.ignoreMarkup = true;
			return super.getPrefWidth();
		} finally {
			prefSizeLayout.ignoreMarkup = false;
		}
	}
	public float getPrefHeight() {
		try {
			prefSizeLayout.ignoreMarkup = true;
			return super.getPrefHeight();
		} finally {
			prefSizeLayout.ignoreMarkup = false;
		}
	}
}
