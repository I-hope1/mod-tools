package modtools.utils.ui;

import arc.scene.ui.layout.Cell;
import arc.util.Strings;

public class FormatHelper {
	/** {@link Cell#unset} */
	public static final float unset = Float.NEGATIVE_INFINITY;
	public static String fixedAny(Object value) {
		if (value instanceof Float) return fixed((float) value);
		return value.toString();
	}

	/**
	 * 如果不是{@link #unset}就fixed
	 * @return <b color="gray">UNSET</b> if value equals {@link #unset}
	 */
	public static String fixedUnlessUnset(float value) {
		if (value == unset) return "[gray]UNSET[]";
		return fixed(value);
	}
	public static String fixed(float value) {
		return fixed(value, 1);
	}
	public static String fixed(double value, int digits) {
		return fixed((float) value, digits);
	}

	public static String fixed(float value, int digits) {
		if (Float.isNaN(value)) return "NAN";
		if (Float.isInfinite(value)) return value > 0 ? "+∞" : "-∞";
		return Strings.autoFixed(value, digits);
	}
}
