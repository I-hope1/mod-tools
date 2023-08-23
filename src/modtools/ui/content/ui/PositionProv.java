package modtools.ui.content.ui;

import arc.func.Prov;
import arc.math.geom.Vec2;

public class PositionProv implements Prov<CharSequence> {
	float lastX, lastY;
	String lastPos;
	public Prov<Vec2> posProv;
	public String     delimiter;
	public PositionProv(Prov<Vec2> posProv) {
		this(posProv, "\n");
	}
	public PositionProv(Prov<Vec2> posProv, String delimiter) {
		this.posProv = posProv;
		this.delimiter = delimiter;
	}
	public String get() {
		Vec2 pos = posProv.get();
		if (lastPos == null || lastX != pos.x || lastY != pos.y) {
			lastPos = "(" + pos.x + delimiter + pos.y + ')';
		}
		return lastPos;
	}
}
