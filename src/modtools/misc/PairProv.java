package modtools.misc;

import arc.func.Prov;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import modtools.utils.ui.CellTools;

import static modtools.utils.ui.FormatHelper.*;

public class PairProv implements Prov<CharSequence> {
	public final Prov<Vec2> vecProv;
	public final String     delimiter;
	public final boolean    parentheses;
	public PairProv(Prov<Vec2> vecProv, String delimiter) {
		this(vecProv, delimiter, true);
	}
	public PairProv(Prov<Vec2> vecProv, boolean parentheses) {
		this(vecProv, "\n", parentheses);
	}
	public PairProv(Prov<Vec2> vecProv, String delimiter, boolean parentheses) {
		this.vecProv = vecProv;
		this.delimiter = delimiter;
		this.parentheses = parentheses;
	}

	float lastX, lastY;
	String lastStr;
	public String getString(Vec2 vec) {
		return STR."(\{fixed(vec.x)}\{delimiter}\{fixed(vec.y)})";
	}
	public final String get() {
		Vec2 vec;
		try {
			vec = vecProv.get();
		} catch (Throwable e) { return "[red]ERROR"; }

		if (lastStr == null || !Mathf.equal(lastX, vec.x) || !Mathf.equal(lastY, vec.y)) {
			lastStr = getString(vec);
		}
		return lastStr;
	}
	/** {@link CellTools#unset}会被解析  */
	public static class SizeProv extends PairProv {
		public SizeProv(Prov<Vec2> vecProv) {
			this(vecProv, "[accent]×[]");
		}
		public SizeProv(Vec2 vec2) {
			this(() -> vec2, "[accent]×[]");
		}
		public SizeProv(Prov<Vec2> vecProv, String delimiter) {
			super(vecProv, delimiter, false);
		}
		public String getString(Vec2 vec) {
			return STR."\{fixedUnlessUnset(vec.x)}\{delimiter}\{fixedUnlessUnset(vec.y)}";
		}
	}
}
