package modtools.annotations.processors;

import com.google.auto.service.AutoService;
import modtools.annotations.*;
import modtools.annotations.unsafe.Replace;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.util.Set;

import static modtools.annotations.PrintHelper.SPrinter.err;

@SupportedOptions("org.gradle.annotation.processing.incremental")
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
			Replace.extendingFunc(processingEnv);
		} catch (Throwable e) {
			err(e);
		} finally {
			Times.printElapsed("Compiler initialed in @ms");
		}
	}
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		Replace.process(roundEnv.getRootElements());
		return true;
	}
	public SourceVersion getSupportedSourceVersion() {
		return SourceVersion.latestSupported();
	}
	public Set<String> getSupportedAnnotationTypes() {
		return Set.of("java.lang.Override");
	}
	public Set<String> getSupportedOptions() {
		return Set.of("targetVersion", "org.gradle.annotation.processing.incremental");
	}
}
