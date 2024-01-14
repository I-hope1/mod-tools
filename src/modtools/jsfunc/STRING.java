package modtools.jsfunc;

import modtools.utils.StringUtils;

public interface STRING {
	static String substring(String str, int start, int end) {
		return StringUtils.substring(str, start, end);
	}
	static void changeByte(String from, String to) {
		StringUtils.changeByte(from, to);
	}
}
