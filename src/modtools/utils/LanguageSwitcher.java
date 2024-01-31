package modtools.utils;

import arc.Core;
import arc.files.Fi;
import arc.struct.Seq;
import arc.util.*;
import arc.util.io.PropertiesUtils;
import modtools.HopeConstant.MODS;
import modtools.IntVars;
import modtools.struct.v6.AThreads;

import java.util.Locale;

public class LanguageSwitcher {
	public static final Locale
	 defaultLocale = Locale.getDefault(),
	 toLocale      = defaultLocale == Locale.ENGLISH ? Locale.CHINA : Locale.ENGLISH;
	public static final I18NBundle origin = Core.bundle;
	public static void switchLanguage() {
		Fi     handle = Core.files.internal("bundles/bundle");
		Locale locale = Locale.getDefault() == defaultLocale ? toLocale : defaultLocale;
		Locale.setDefault(locale);
		I18NBundle newBundle = I18NBundle.createBundle(handle, locale);
		addKeyToBundle(newBundle);
		IntVars.async(() -> {
			for (var k : origin.getKeys()) {
				StringUtils.changeByte(origin.get(k), newBundle.get(k));
			}
		});
		// Core.bundle = newBundle;
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
