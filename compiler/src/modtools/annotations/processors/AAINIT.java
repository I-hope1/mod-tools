package modtools.annotations.processors;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import modtools.annotations.HopeReflect;
import modtools.annotations.unsafe.Replace;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.io.*;
import java.util.*;

import static modtools.annotations.PrintHelper.SPrinter.*;

@AutoService({Processor.class})
public class AAINIT extends AbstractProcessor {
	public static boolean    hasMindustry = true;
	public static Properties properties   = new Properties();

	static {
		try {
			HopeReflect.load();
			Replace.replaceSource();
			loadProperties();
			hasMindustry = !properties.containsKey("hasMindustry") || properties.getProperty("hasMindustry").equals("true");
		} catch (Throwable e) {err(e);}
	}

	static void loadProperties() throws IOException {
		File file = new File("gradle.properties");
		if (!file.exists()) {
			println(file.createNewFile()
			 ? "Created New File: @"
			 : "Could not create file: @", file.getAbsoluteFile());
			return;
		}
		properties.load(new FileInputStream(file));
	}
	public synchronized void init(ProcessingEnvironment processingEnv) {
		try {
			Replace.extendingFunc(((JavacProcessingEnvironment) processingEnv).getContext());
		} catch (Throwable e) {err(e);}
	}


	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {return true;}
	public SourceVersion getSupportedSourceVersion() {
		return SourceVersion.latestSupported();
	}
	public Set<String> getSupportedAnnotationTypes() {
		return Set.of();
	}
}
