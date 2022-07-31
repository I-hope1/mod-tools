package modtools.utils;

import arc.math.geom.Vec2;
import arc.scene.Element;

public class Tools {
	public static boolean validPosInt(String text) {
		return text.matches("^\\d+(\\.\\d*)?([Ee]\\d+)?$");
	}

	public static int asInt(String text) {
		return (int) Float.parseFloat(text);
	}

	// 去除颜色
	public static String format(String s) {
		return s.replaceAll("\\[(\\w+?)\\]", "[\u0001$1]");
	}


	public static int len(String s) {
		return s.split("").length - 1;
	}

	public static Vec2 getAbsPos(Element el) {
		if (true) return el.localToStageCoordinates(new Vec2(0, 0));
		Vec2 vec2 = new Vec2(el.x, el.y);
		while (el.parent != null) {
			el = el.parent;
			vec2.add(el.x, el.y);
		}
		return vec2;
	}
}
