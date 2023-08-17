package modtools.ui.components.input.highlight;

import arc.graphics.g2d.Font;

public interface SyntaxDrawable {
	float alpha();
	Font font();

	default int cursor() {return 0;}
	default float getRelativeX(int pos) {return 0;}
	default float getRelativeY(int pos) {return 0;}

	void drawMultiText(CharSequence displayText, int start, int max);
}
