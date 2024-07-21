package modtools.ui.comp;

import arc.scene.ui.TextArea;
import arc.scene.ui.layout.Table;

public class IntTextArea extends Table {
	public TextArea area;

	public String linesStr() {
		int first = area.getFirstLineShowing(),
				len = area.getLinesShowing() - 1,
				now = area.getCursorLine();
		var str = new StringBuilder("[lightgray]");
		for (int i = 0; i < len; i++) {
			int current = i + first + 1;
			if (i + first == now) {
				str.append("[gold]");
				str.append(current);
				str.append("[]");
			} else {
				str.append(current);
			}

			str.append("\n");
		}
		return str + "";
	}

	public IntTextArea(String text, float w, float h) {
		area = new TextArea(text);
		label(() -> linesStr());
		add(area).size(w, h);
	}
}
