package modtools.annotations.reflect;

import com.sun.tools.javac.code.Symbol.ClassSymbol;
import modtools.annotations.TreeUtils;

public interface ReflectUtils extends TreeUtils {
	ClassSymbol[] CLASSES = {null, null};
	default ClassSymbol FIELD() {
		if (CLASSES[0] == null) CLASSES[0] = findClassSymbolByBoot("java.lang.reflect.Field");
		return CLASSES[0];
	}
	default ClassSymbol FIELD_UTILS() {
		if (CLASSES[1] == null) CLASSES[1] = findClassSymbol("modtools.utils.reflect.FieldUtils");
		return CLASSES[1];
	}
}
