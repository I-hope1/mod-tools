package modtools.annotations.processors;

import com.sun.tools.javac.code.Symbol.PackageSymbol;
import modtools.annotations.*;

import java.util.*;

// @AutoService(Processor.class)
public class MovePackageProc extends BaseProcessor<PackageSymbol> {
	public static Map<String, String> mapping = new HashMap<>();
	public void dealElement(PackageSymbol element) throws Throwable {
		MoveToPackage move = element.getAnnotation(MoveToPackage.class);
		if (move == null) return;
		mapping.put(element.toString(), move.targetPackage());
	}
	public Set<Class<?>> getSupportedAnnotationTypes0() {
		return Set.of(MoveToPackage.class);
	}
}
