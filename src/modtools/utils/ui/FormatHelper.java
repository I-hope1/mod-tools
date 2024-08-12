package modtools.utils.ui;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Font;
import arc.scene.Group;
import arc.scene.event.Touchable;
import arc.scene.style.*;
import arc.struct.*;
import arc.util.*;
import mindustry.Vars;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.ui.control.HKeyCode;
import modtools.ui.style.DelegatingDrawable;
import modtools.utils.Tools;

import java.util.Map;

import static modtools.content.ui.ShowUIList.*;

public class FormatHelper {

	public static final boolean                     withPrefix   = true;
	public static final ObjectMap<String, Class<?>> keyToClasses = ObjectMap.of(
	 "Styles", Styles.class,
	 "Tex", Tex.class,
	 "Color", Color.class,
	 "Pal", Pal.class
	);

	public static String fixedAny(Object value) {
		if (value instanceof Float) return fixed((float) value);
		return value.toString();
	}

	/**
	 * 如果不是{@link CellTools#unset}就fixed
	 * @return "<b color="gray">UNSET</b>" if value equals {@link CellTools#unset}
	 */
	public static String fixedUnlessUnset(float value) {
		if (value == CellTools.unset) return "[gray]UNSET[]";
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

	// 去除颜色
	public static String stripColor(String s) {
		return s.replace("[", "[[");
	}

	// 一些对象的字符串转换
	public static CharSequence touchable(Touchable touchable) {
		return switch (touchable) {
			case enabled -> "Enabled";
			case disabled -> "Disabled";
			case childrenOnly -> "Children Only";
		};
	}
	public static CharSequence align(int align) {
		return Strings.capitalize(Align.toString(align).replace(',', '-'));
	}
	public static String color(Color color) {
		return color.toString().toUpperCase();
	}
	public static String getUIKeyOrNull(Object val) {
		if (val instanceof DelegatingDrawable delegting) return delegting.toString();

		return val instanceof Drawable icon && iconKeyMap.containsKey(icon) ?
		 iconKeyMap.get(icon)

		 : val instanceof Drawable drawable && styleIconKeyMap.containsKey(drawable) ?
		 styleIconKeyMap.get(drawable)

		 : val instanceof Drawable drawable && texKeyMap.containsKey(drawable) ?
		 texKeyMap.get(drawable)

		 : val instanceof Style s && styleKeyMap.containsKey(s) ?
		 styleKeyMap.get(s)

		 : val instanceof Color c && colorKeyMap.containsKey(c) ?
		 colorKeyMap.get(c)

		 : val instanceof Group g && uiKeyMap.containsKey(g) ?
		 (withPrefix ? "ui." : "") + uiKeyMap.get(g)

		 : val instanceof Font f && fontKeyMap.containsKey(f) ?
		 (withPrefix ? "Fonts." : "") + fontKeyMap.get(f)

		 : null;
	}
	/** 如果找不到，就抛出一个错误  */
	public static String getUIKey(Object val) {
		String res = getUIKeyOrNull(val);
		if (res == null) Tools._throw();
		return res;
	}
	public static <T> T lookupUI(String key) {
		int i = key.indexOf('.');
		if (i == -1) return null;
		Class<?> clazz = keyToClasses.get(key.substring(0, i));
		if (clazz == null) return null;
		return Reflect.get(clazz, key.substring(i + 1));
	}
	public static <T> T lookupKey(Map<T, String> map, String value) {
		for (var entry : map.entrySet()) {
			if (entry.getValue().equals(value)) return entry.getKey();
		}
		return Tools._throw();
	}
	public static String fieldFormat(String s) {
		if (s.indexOf('.') == -1) return "[accent]" + s;
		return s.replaceAll("^(\\w+?)\\.(\\w+)", "[accent]$2 []([slate]$1[])");
	}

	private static final boolean       DEBUG         = false;
	private static final StringBuilder sb            = new StringBuilder();
	public static final  char          varIdentifier = '%';
	/** 一些游戏一开始就能确定的var */
	public static final  StringMap     varMap        = StringMap.of(
	 "MENU_KEY", Core.bundle.get(Vars.mobile ? "longPress" : "rightClick")
	);
	public static CharSequence parseVars(String s) {
		sb.setLength(0);
		StringMap map = new StringMap();
		HKeyCode.eachAllKey((key, value) -> {
			map.put(key, String.valueOf(value));
		});
		if (DEBUG) Log.info(map);
		int startIndex = -1;
		for (int i = 0, len = s.length(); i < len; i++) {
			if (startIndex == -1) {
				if (s.charAt(i) == '%') {
					startIndex = i + 1;
				} else {
					sb.append(s.charAt(i));
				}
			} else if (s.charAt(i) == varIdentifier) {
				String key = s.substring(startIndex, i);
				startIndex = -1;
				if (DEBUG) Log.info(key);
				if (map.containsKey(key)) {
					sb.append(map.get(key));
				} else if (varMap.containsKey(key)) {
					sb.append(varMap.get(key));
				} else {
					sb.append('%').append(key).append('%');
				}
			}
		}
		return sb;
	}
}
