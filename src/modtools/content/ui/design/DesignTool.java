package modtools.content.ui.design;

import modtools.editor.HTool;

public enum DesignTool implements HTool {
	move, selection, rectangle, pen, text, hand, eraser;

	public boolean edit;
	public boolean draggable;
	public int mode;

	public void touched(int x, int y) {

	}

	public void touchedLine(int startx, int starty, int x, int y) {
	}
}
