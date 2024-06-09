package modtools.utils;

import arc.graphics.Color;
import arc.graphics.g2d.Font;
import arc.scene.Group;
import arc.scene.event.Touchable;
import arc.scene.style.*;
import arc.util.*;
import modtools.ui.content.ui.ShowUIList;

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
		return val instanceof Drawable icon && ShowUIList.iconKeyMap.containsKey(icon) ?
		 (withPrefix ? "Icon." : "") + ShowUIList.iconKeyMap.get(icon)

		 : val instanceof Drawable drawable && ShowUIList.styleIconKeyMap.containsKey(drawable) ?
		 (withPrefix ? "Styles." : "") + ShowUIList.styleIconKeyMap.get(drawable)

		 : val instanceof Drawable drawable && ShowUIList.texKeyMap.containsKey(drawable) ?
		 (withPrefix ? "Tex." : "") + ShowUIList.texKeyMap.get(drawable)

		 : val instanceof Style s && ShowUIList.styleKeyMap.containsKey(s) ?
		 (withPrefix ? "Styles." : "") + ShowUIList.styleKeyMap.get(s)

		 : val instanceof Color c && ShowUIList.colorKeyMap.containsKey(c) ?
		 ShowUIList.colorKeyMap.get(c)

		 : val instanceof Group g && ShowUIList.uiKeyMap.containsKey(g) ?
		 (withPrefix ? "Vars.ui." : "") + ShowUIList.uiKeyMap.get(g)

		 : val instanceof Font f && ShowUIList.fontKeyMap.containsKey(f) ?
		 (withPrefix ? "Fonts." : "") + ShowUIList.fontKeyMap.get(f)

		 : Tools._throw();
	}
}
