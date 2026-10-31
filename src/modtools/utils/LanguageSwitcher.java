package modtools.utils;

import arc.Core;
import arc.files.Fi;
import arc.struct.Seq;
import arc.util.*;
import arc.util.io.PropertiesUtils;
import modtools.Constants.MODS;

import java.util.Locale;

public class LanguageSwitcher {
	public static final Locale
	 defaultLocale = Locale.getDefault(),
	 toLocale      = defaultLocale == Locale.ENGLISH ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
	public static final I18NBundle origin = Core.bundle;
	public static void switchLanguage() {
		switchLanguage(Locale.getDefault() == defaultLocale ? toLocale : defaultLocale);
	}
	public static void switchLanguage(Locale locale) {
		Fi     handle = Core.files.internal("bundles/bundle");
		Locale.setDefault(locale);
		I18NBundle newBundle = I18NBundle.createBundle(handle, locale);
		addKeyToBundle(newBundle);
		// 解析 bundle 是 IO，留在调用线程；但「改写存量 String」必须和逐帧读文本的渲染线程串行，
		// 所以只有这一段挪到主线程（调用方普遍是 async 出来的后台线程）。
		if (Core.app.isOnMainThread()) copyContent(newBundle);
		else Core.app.post(() -> copyContent(newBundle));
		// Core.bundle = newBundle;
	}
	private static void copyContent(I18NBundle newBundle) {
		for (String k : origin.getKeys()) {
			StringUtils.copyByte(origin.get(k), newBundle.get(k));
		}
	}
	private static void addKeyToBundle(I18NBundle bundle) {
		//add new keys to each bundle
		while (bundle != null) {
			String str    = bundle.getLocale().toString();
			String locale = "bundle" + (str.isEmpty() ? "" : "_" + str);
			assert MODS.bundles != null;
			for (Fi file : MODS.bundles.get(locale, Seq::new)) {
				try {
					PropertiesUtils.load(bundle.getProperties(), file.reader());
				} catch (Throwable e) {
					Log.err("Error loading bundle: " + file + "/" + locale, e);
				}
			}
			bundle = bundle.getParent();
		}
	}
}
