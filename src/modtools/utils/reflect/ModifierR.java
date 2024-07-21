package modtools.utils.reflect;

import modtools.utils.ui.ReflectTools.MarkedCode;

/** @see java.lang.reflect.Modifier */
public enum ModifierR implements MarkedCode {
	PUBLIC, PRIVATE, PROTECTED, STATIC, FINAL, SYNCHRONIZED, VOLATILE, TRANSIENT,
	NATIVE, INTERFACE, ABSTRACT, STRICT;
	public int code() {
		return ordinal();
	}
}
