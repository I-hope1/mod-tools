package modtools.utils;

import arc.graphics.Color;
import arc.graphics.g2d.Font;
import arc.scene.Group;
import arc.scene.event.Touchable;
import arc.scene.style.*;
import arc.struct.ObjectMap;
import arc.util.*;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.ui.style.DelegetingDrawable;

import java.util.Map;

import static modtools.ui.content.ui.ShowUIList.*;

public class StringHelper {
	private static final boolean withPrefix = true;
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
	public static String getUIKey(Object val) {
		if (val instanceof DelegetingDrawable delegting) return delegting.toString();
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

		 : Tools._throw();
	}
	public static <T> T lookupUI(String key) {
		int i = key.indexOf('.');
		if (i == -1) return null;
		Class<?> clazz = keyToClasses.get(key.substring(0, i));
		if (clazz == null) return null;
		return Reflect.get(clazz, key.substring(i + 1));
	}
	static ObjectMap<String, Class<?>> keyToClasses = ObjectMap.of(
	 "Styles", Styles.class,
	 "Tex", Tex.class,
	 "Color", Color.class,
	 "Pal", Pal.class
	);
	private static <T> T lookupKey(Map<T, String> map, String value) {
		for (var entry : map.entrySet()) {
			if (entry.getValue().equals(value)) return entry.getKey();
		}
		return Tools._throw();
	}

	public static String fieldFormat(String s) {
		if (s.indexOf('.') == -1) return "[accent]" + s;
		return s.replaceAll("^(\\w+?)\\.(\\w+)", "[accent]$2 []([slate]$1[])");
	}
}
