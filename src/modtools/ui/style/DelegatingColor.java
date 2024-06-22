package modtools.ui.style;

import arc.graphics.Color;

public class DelegatingColor {
	private final Color color;
	public DelegatingColor(Color color) {
		this.color = color;
	}
	public void set(Color color) {
		this.color.set(color);
	}
	public Color get() {
		return color;
	}
}
