package modtools.utils;

import arc.graphics.Color;
import arc.graphics.g2d.Font;
import arc.scene.Group;
import arc.scene.event.Touchable;
import arc.scene.style.*;
import arc.struct.ObjectMap;
import arc.util.*;
import dalvik.system.VMRuntime;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.HopeConstant.STRING;
import modtools.android.HiddenApi;
import modtools.ui.style.DelegatingDrawable;

import java.util.Map;

import static ihope_lib.MyReflect.unsafe;
import static modtools.ui.content.ui.ShowUIList.*;

public interface StringUtils {
	boolean withPrefix = true;
	ObjectMap<String, Class<?>> keyToClasses = ObjectMap.of(
	 "Styles", Styles.class,
	 "Tex", Tex.class,
	 "Color", Color.class,
	 "Pal", Pal.class
	);
	static void changeByte(String from, String to) {
		if (OS.isAndroid) changeByteAndroid(from, to);
		else changeByteDesktop(from, to);
	}
	/** For Desktop */
	private static void changeByteDesktop(String from, String to) {
		unsafe.putObject(from, STRING.VALUE, unsafe.getObject(to, STRING.VALUE));
		unsafe.putByte(from, STRING.CODER, unsafe.getByte(to, STRING.CODER));
	}
	/** For Android */
	private static void changeByteAndroid(String from, String to) {
		String[] tmp     = {from, to};
		String[] strings = (String[]) VMRuntime.getRuntime().newNonMovableArray(String.class, 2);
		System.arraycopy(tmp, 0, strings, 0, 2);

		// Log.info("prev count: " + from.length());
		// unsafe.putInt(from, STRING_COUNT, unsafe.getInt(to, STRING_COUNT));
		// Log.info("now count: " + from.length());
		// Log.info("COUNT_OFF: @", STRING_COUNT);
		long src_address  = HiddenApi.addressOf(strings);
		long dest_address = src_address + HiddenApi.IBYTES;
		// unsafe.putLong(unsafe.getInt(src_address) + 12, unsafe.getLong(
		// 	 unsafe.getInt(dest_address) + 12));
		/* unsafe.copyMemory(unsafe.getInt(address2) + 8,
			 unsafe.getInt(address) + 8, 2); */
		for (int i = 8; i < 100; i += 2) {
			// unsafe.putLong(from, i, unsafe.getLong(to, i));
			// Log.info("res[@]: @ -> @", i, from, to);
			if (to.equals(from)) break;
			unsafe.copyMemory(unsafe.getInt(dest_address) + i,
			 unsafe.getInt(src_address) + i, 2);
		}
	}

	@SuppressWarnings("StringRepeatCanBeUsed")
	static String repeat(String str, int count) {
		try {
			return str.repeat(count);
		} catch (Throwable e) {
			if (count == 1) return str;
			StringBuilder buffer = new StringBuilder();
			for (int i = 0; i < count; i++) {
				buffer.append(str);
			}
			return buffer.toString();
		}
	}

	static String substring(String str, int start, int end) {
		if (start < 0) start += str.length();
		if (end < 0) end += str.length();
		return str.substring(start, end);
	}
	static CharSequence touchable(Touchable touchable) {
		return switch (touchable) {
			case enabled -> "Enabled";
			case disabled -> "Disabled";
			case childrenOnly -> "Children Only";
		};
	}
	static CharSequence align(int align) {
		return Strings.capitalize(Align.toString(align).replace(',', '-'));
	}
	static String getUIKeyOrNull(Object val) {
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

	static String getUIKey(Object val) {
		String res = getUIKeyOrNull(val);
		if (res == null) Tools._throw();
		return res;
	}
	static <T> T lookupUI(String key) {
		int i = key.indexOf('.');
		if (i == -1) return null;
		Class<?> clazz = keyToClasses.get(key.substring(0, i));
		if (clazz == null) return null;
		return Reflect.get(clazz, key.substring(i + 1));
	}
	static <T> T lookupKey(Map<T, String> map, String value) {
		for (var entry : map.entrySet()) {
			if (entry.getValue().equals(value)) return entry.getKey();
		}
		return Tools._throw();
	}
	static String fieldFormat(String s) {
		if (s.indexOf('.') == -1) return "[accent]" + s;
		return s.replaceAll("^(\\w+?)\\.(\\w+)", "[accent]$2 []([slate]$1[])");
	}
}
