package modtools.annotations.processors;

import com.google.auto.service.AutoService;
import modtools.annotations.*;
import modtools.annotations.unsafe.Replace;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.util.Set;

import static modtools.annotations.PrintHelper.SPrinter.*;

@AutoService(Processor.class)
public class AINIT extends AbstractProcessor {
	public static boolean hasMindustry = true;

	static {
		try {
			Times.mark();
			HopeReflect.load();
			Replace.init();
		} catch (Throwable e) { err(e); }
	}

	public synchronized void init(ProcessingEnvironment processingEnv) {
		try {
			HopeReflect.openModule();
			Replace.extendingFunc(processingEnv);
		} catch (Throwable e) {
			err(e);
		} finally {
			Times.printElapsed("Compiler initialed in @ms");
		}
	}
	boolean init = false;
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		if (init) return false;
		init = true;
		// println("AINIT process");
		Replace.process(roundEnv.getRootElements());
		return false;
	}
	public SourceVersion getSupportedSourceVersion() {
		return SourceVersion.latestSupported();
	}

	public Set<String> getSupportedOptions() {
		return Set.of(Replace.forceJavaVersionOri);
	}
	// 这里的override用于只是启用process
	public Set<String> getSupportedAnnotationTypes() {
		return Set.of("*");
	}
}
