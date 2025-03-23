package modtools.ui.comp.input.highlight;

import arc.graphics.g2d.Font;

public interface SyntaxDrawable {
	default float alpha() { return 0; }
	default int cursor() { return 0; }
	default Font font() { return null; }

	String getText();
	void drawMultiText(CharSequence displayText, int start, int max);

}
