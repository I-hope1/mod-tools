package modtools.ui.components.input.highlight;

import arc.graphics.g2d.Font;

public interface SyntaxDrawable {
	float alpha();
	Font font();
	void drawMultiText(CharSequence displayText, int start, int max);
}
