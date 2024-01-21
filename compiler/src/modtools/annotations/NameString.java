package modtools.annotations;

import com.sun.tools.javac.util.Name;

import static modtools.annotations.BaseProcessor.names;

public interface NameString {
	default Name ns(String s) {
		return ns0(s);
	}
	static Name ns0(String s) {
		return names.fromString(s);
	}
}
