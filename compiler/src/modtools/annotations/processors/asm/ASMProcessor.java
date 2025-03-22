package modtools.annotations.processors.asm;

import com.sun.tools.javac.code.Symbol.ClassSymbol;
import modtools.annotations.BaseProcessor;
import modtools.annotations.asm.HAccessor.*;

import java.util.Set;

public class ASMProcessor extends BaseProcessor<ClassSymbol> {
	public void dealElement(ClassSymbol element) throws Throwable {
		log.error("Todo!!");
	}
	public Set<Class<?>> getSupportedAnnotationTypes0() {
		return Set.of(HField.class, HMethod.class);
	}
}
