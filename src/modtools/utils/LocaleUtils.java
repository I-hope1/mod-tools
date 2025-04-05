package modtools.utils;

import mindustry.ui.dialogs.LanguageDialog;

import java.util.Locale;

import static mindustry.Vars.locales;

/** @see mindustry.ui.dialogs.LanguageDialog  */
@SuppressWarnings("deprecation")
public class LocaleUtils {
	public static Locale findClosestLocale() {
		//check exact locale
		for (Locale l : locales) {
			if (l.equals(Locale.getDefault())) {
				return l;
			}
		}

		//find by language
		for (Locale l : locales) {
			if (l.getLanguage().equals(Locale.getDefault().getLanguage())) {
				return l;
			}
		}

		return Locale.ENGLISH;
	}
	public static final String noneLocStr = "null";
	public static final Locale none = new Locale(noneLocStr);

	private static Locale lastLocale;
	/** @see LanguageDialog#getLocale()   */
	public static Locale getLocale(String loc) {
		if (loc == null || loc.isEmpty() || loc.equals(noneLocStr)) {
			return none;
		}
		if (loc.equals("default")) {
			return findClosestLocale();
		}

		if (lastLocale == null || !lastLocale.toString().equals(loc)) {
			if (loc.contains("_")) {
				String[] split = loc.split("_");
				lastLocale = new Locale(split[0], split[1]);
			} else {
				lastLocale = new Locale(loc);
			}
		}

		return lastLocale;
	}
	public static String getDisplayName(Locale locale) {
		if (locale == none) return noneLocStr;
		return LanguageDialog.getDisplayName(locale);
	}
}
