package modtools.content.ui.design;

public enum DesignTool {
	move, selection, rectangle, pen, text, hand, eraser;

	public boolean edit;
	public boolean draggable;
	public int mode;

	public void touched(int x, int y) {

	}

	public void touchedLine(int startx, int starty, int x, int y) {
	}
}
