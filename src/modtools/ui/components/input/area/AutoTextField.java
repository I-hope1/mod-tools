package modtools.ui.components.input.area;

import arc.Core;
import arc.graphics.g2d.Font;
import arc.scene.Scene;
import arc.scene.style.Drawable;
import arc.scene.ui.TextField;
import arc.util.Time;

import static modtools.ui.components.input.area.TextAreaTab.MOMO_STYLE;

/** 根据文本，自动调整大小  */
public class AutoTextField extends TextField {
	public AutoTextField() {
		setStyle(MOMO_STYLE);
	}

	public AutoTextField(String text) {
		super(text);
	}

	{
		changed(this::resize0);
	}

	public void setText(String str) {
		super.setText(str);
		resize0();
	}
	private void resize0() {
		Time.runTask(0, () -> setWidth(getPrefWidth()));
		if (parent != null) parent.invalidateHierarchy();
	}
	public float getPrefWidth() {
		float val = textOffset;
		try {
			val += glyphPositions.peek() - glyphPositions.get(0);
			Drawable background = getBack();
			if (background != null) val += background.getLeftWidth();
		} catch (Exception ignored) {}
		return Math.min(val + 14, Core.graphics.getWidth() * 0.7f);
	}

	Drawable getBack() {
		Scene   stage   = getScene();
		boolean focused = stage != null && stage.getKeyboardFocus() == this;
		return (disabled && style.disabledBackground != null) ? style.disabledBackground
		 : (!isValid() && style.invalidBackground != null) ? style.invalidBackground
		 : ((focused && style.focusedBackground != null) ? style.focusedBackground : style.background);
	}

	protected void drawCursor(Drawable cursorPatch, Font font, float x, float y) {
		cursorPatch.draw(
		 x + textOffset + glyphPositions.get(cursor) - glyphPositions.get(visibleTextStart) + fontOffset + font.getData().cursorX,
		 y - textHeight - font.getDescent(), cursorPatch.getMinWidth(), textHeight);
	}

	/*public static Font luculent;

	public static void load() {
		Core.assets.load("luculent", Font.class, new FreetypeFontLoader.FreeTypeFontLoaderParameter(IntVars.data.child("data").child("luculent.ttf").path(), new FreeTypeFontGenerator.FreeTypeFontParameter() {{
			size = 30;
			characters = "\0";
		}})).loaded = f -> luculent = f;
	}*/
}
