package modtools.ui.content.ui;

import arc.func.Prov;
import arc.math.Mathf;
import arc.math.geom.Vec2;

import static modtools.utils.ui.FormatHelper.fixed;

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
		if (lastPos == null || !Mathf.equal(lastX, pos.x) || !Mathf.equal(lastY, pos.y)) {
			lastPos = "(" + fixed(pos.x) + delimiter + fixed(pos.y) + ')';
		}
		return lastPos;
	}
}
