package modtools.ui.comp.input.area;

import arc.Core;
import arc.math.Mathf;
import arc.scene.Scene;
import arc.scene.style.Drawable;
import arc.scene.ui.TextField;

/** 根据文本，自动调整大小 */
public class AutoTextField extends TextField {
	public AutoTextField() {
	}

	public static final String NULL_STR = "'null";

	public AutoTextField(String text) {
		super(text);
	}

	{
		changed(this::resize0);
	}

	public void setTextCheck(String str) {
		if (Core.scene.getKeyboardFocus() == this) return;
		if (str == null) setText(NULL_STR);
		setText(str);
	}

	public void setText(String str) {
		super.setText(str);
		resize0();
	}
	private void resize0() {
		Core.app.post(() -> setWidth(getPrefWidth()));
		if (parent != null) parent.invalidateHierarchy();
	}
	public float getPrefWidth() {
		float bgPad = 10;
		if (getBack() instanceof Drawable back) bgPad += back.getLeftWidth() + back.getRightWidth();
		return Mathf.clamp(layout.width + bgPad + fontOffset + textOffset, 70, Core.graphics.getWidth() * 0.7f);
	}

	Drawable getBack() {
		Scene   stage   = getScene();
		boolean focused = stage != null && stage.getKeyboardFocus() == this;
		return (disabled && style.disabledBackground != null) ? style.disabledBackground
		 : (!isValid() && style.invalidBackground != null) ? style.invalidBackground
		 : ((focused && style.focusedBackground != null) ? style.focusedBackground : style.background);
	}

	/*public static Font luculent;

	public static void load() {
		Core.assets.load("luculent", Font.class, new FreetypeFontLoader.FreeTypeFontLoaderParameter(IntVars.data.child("data").child("luculent.ttf").path(), new FreeTypeFontGenerator.FreeTypeFontParameter() {{
			size = 30;
			characters = "\0";
		}})).loaded = f -> luculent = f;
	}*/
}
